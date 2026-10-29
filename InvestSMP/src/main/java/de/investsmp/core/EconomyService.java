package de.investsmp.core;

import de.investsmp.db.Database;
import de.investsmp.util.Money;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Zentrale Geldlogik. Jede Buchung läuft in einer DB-Transaktion und wird im Transaktionsprotokoll
 * festgehalten. Beträge sind Cent (long).
 */
public final class EconomyService {

    public enum Result { OK, INSUFFICIENT, INVALID, NO_ACCOUNT, SAME_ACCOUNT, LIMIT, ERROR }

    public record Account(UUID id, String name) { }

    public record TxEntry(long ts, String name, String type, long amount, long balanceAfter, String actor, String note) { }

    public record Rich(String name, long cents) { }

    public record Stats(long totalMoney, long totalInvested, int accounts) { }

    private final Database db;
    private final LongSupplier clock;
    private volatile MoneyBackend backend;

    public EconomyService(Database db, MoneyBackend backend, LongSupplier clock) {
        this.db = db;
        this.backend = backend;
        this.clock = clock;
    }

    public void setBackend(MoneyBackend backend) {
        this.backend = backend;
    }

    public String backendName() {
        return backend.name();
    }

    public boolean isInternal() {
        return backend.isInternal();
    }

    // ---------------------------------------------------------------- Konten

    public void ensureAccount(UUID id, String name, long startingBalance, boolean autocollect) {
        db.run(() -> {
            long now = clock.getAsLong();
            int created = db.update("INSERT OR IGNORE INTO accounts(uuid,name,balance,last_accrual,autocollect,created) "
                    + "VALUES(?,?,0,?,?,?)", id.toString(), name, now, autocollect ? 1 : 0, now);
            if (created == 1) {
                if (!backend.hasAccount(id)) {
                    backend.createAccount(id, name);
                }
                if (startingBalance > 0 && backend.isInternal() && backend.deposit(id, startingBalance)) {
                    log(id, "START", startingBalance, "system", "Startguthaben");
                }
            } else {
                db.update("UPDATE accounts SET name=? WHERE uuid=? AND name<>?", name, id.toString(), name);
                if (!backend.hasAccount(id)) {
                    backend.createAccount(id, name);
                }
            }
        });
    }

    public boolean hasAccount(UUID id) {
        return backend.hasAccount(id);
    }

    public long balance(UUID id) {
        return backend.balance(id);
    }

    public Optional<Account> findByName(String name) {
        return db.one("SELECT uuid,name FROM accounts WHERE name=? COLLATE NOCASE ORDER BY created DESC LIMIT 1",
                rs -> new Account(UUID.fromString(rs.getString(1)), rs.getString(2)), name);
    }

    public String nameOf(UUID id) {
        return db.one("SELECT name FROM accounts WHERE uuid=?", rs -> rs.getString(1), id.toString())
                .orElse(id.toString());
    }

    public List<Account> accounts() {
        return db.query("SELECT uuid,name FROM accounts ORDER BY name COLLATE NOCASE",
                rs -> new Account(UUID.fromString(rs.getString(1)), rs.getString(2)));
    }

    // -------------------------------------------------------------- Buchungen

    public Result deposit(UUID id, long cents, String type, String actor, String note) {
        if (cents <= 0) {
            return Result.INVALID;
        }
        return db.tx(() -> {
            if (!backend.hasAccount(id)) {
                return Result.NO_ACCOUNT;
            }
            if (!backend.deposit(id, cents)) {
                return Result.LIMIT;
            }
            log(id, type, cents, actor, note);
            return Result.OK;
        });
    }

    public Result withdraw(UUID id, long cents, String type, String actor, String note) {
        if (cents <= 0) {
            return Result.INVALID;
        }
        return db.tx(() -> {
            if (!backend.hasAccount(id)) {
                return Result.NO_ACCOUNT;
            }
            if (!backend.withdraw(id, cents)) {
                return Result.INSUFFICIENT;
            }
            log(id, type, -cents, actor, note);
            return Result.OK;
        });
    }

    /** Atomare Überweisung: entweder beides oder nichts. */
    public Result transfer(UUID from, UUID to, long cents, String fromName, String toName) {
        if (cents <= 0) {
            return Result.INVALID;
        }
        if (from.equals(to)) {
            return Result.SAME_ACCOUNT;
        }
        return db.tx(() -> {
            if (!backend.hasAccount(from) || !backend.hasAccount(to)) {
                return Result.NO_ACCOUNT;
            }
            if (!backend.withdraw(from, cents)) {
                return Result.INSUFFICIENT;
            }
            if (!backend.deposit(to, cents)) {
                backend.deposit(from, cents);
                return Result.LIMIT;
            }
            log(from, "PAY_OUT", -cents, fromName, "an " + toName);
            log(to, "PAY_IN", cents, fromName, "von " + fromName);
            return Result.OK;
        });
    }

    /** Admin: Kontostand auf einen festen Wert setzen (als Differenz gebucht und protokolliert). */
    public Result setBalance(UUID id, long target, String actor, String note) {
        if (target < 0 || target > Money.MAX_BALANCE) {
            return Result.INVALID;
        }
        return db.tx(() -> {
            if (!backend.hasAccount(id)) {
                return Result.NO_ACCOUNT;
            }
            long diff = target - backend.balance(id);
            if (diff == 0) {
                return Result.OK;
            }
            if (diff > 0) {
                if (!backend.deposit(id, diff)) {
                    return Result.LIMIT;
                }
            } else if (!backend.withdraw(id, -diff)) {
                return Result.INSUFFICIENT;
            }
            log(id, "ADMIN_SET", diff, actor, note);
            return Result.OK;
        });
    }

    /** Buchung ohne Protokolleintrag (Rendite-Auszahlungen; werden gesammelt protokolliert). */
    boolean rawDeposit(UUID id, long cents) {
        return cents > 0 && backend.hasAccount(id) && backend.deposit(id, cents);
    }

    void refundQuietly(UUID id, long cents) {
        try {
            backend.deposit(id, cents);
        } catch (RuntimeException ignored) {
            // bestmöglicher Versuch
        }
    }

    public void log(UUID id, String type, long signedCents, String actor, String note) {
        db.update("INSERT INTO transactions(ts,uuid,type,amount,balance_after,actor,note) VALUES(?,?,?,?,?,?,?)",
                clock.getAsLong(), id.toString(), type, signedCents, backend.balance(id), actor,
                note == null ? "" : note);
    }

    // -------------------------------------------------------------- Abfragen

    public List<Rich> top(int n) {
        if (backend.isInternal()) {
            return db.query("SELECT name,balance FROM accounts ORDER BY balance DESC, name LIMIT ?",
                    rs -> new Rich(rs.getString(1), rs.getLong(2)), n);
        }
        List<Rich> all = new ArrayList<>();
        for (Account a : accounts()) {
            all.add(new Rich(a.name(), backend.balance(a.id())));
        }
        all.sort((x, y) -> Long.compare(y.cents(), x.cents()));
        return all.size() > n ? new ArrayList<>(all.subList(0, n)) : all;
    }

    public Stats stats() {
        long money;
        if (backend.isInternal()) {
            money = db.scalar("SELECT COALESCE(SUM(balance),0) FROM accounts");
        } else {
            money = 0;
            for (Account a : accounts()) {
                money += backend.balance(a.id());
            }
        }
        long invested = db.scalar("SELECT COALESCE(SUM(amount),0) FROM investments");
        int count = (int) db.scalar("SELECT COUNT(*) FROM accounts");
        return new Stats(money, invested, count);
    }

    /** Letzte Transaktionen; id == null = alle Spieler. */
    public List<TxEntry> history(UUID id, int limit) {
        String base = "SELECT t.ts, COALESCE(a.name,t.uuid), t.type, t.amount, t.balance_after, t.actor, t.note "
                + "FROM transactions t LEFT JOIN accounts a ON a.uuid=t.uuid ";
        Database.RowMapper<TxEntry> mapper = rs -> new TxEntry(rs.getLong(1), rs.getString(2), rs.getString(3),
                rs.getLong(4), rs.getLong(5), rs.getString(6), rs.getString(7));
        if (id == null) {
            return db.query(base + "ORDER BY t.id DESC LIMIT ?", mapper, limit);
        }
        return db.query(base + "WHERE t.uuid=? ORDER BY t.id DESC LIMIT ?", mapper, id.toString(), limit);
    }
}
