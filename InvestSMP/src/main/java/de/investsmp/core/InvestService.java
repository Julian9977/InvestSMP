package de.investsmp.core;

import de.investsmp.db.Database;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Investitionen und Rendite.
 * Rendite pro Sekunde = investiert * ratePpm / 1.000.000 (in Cent). Der Bruchteil-Rest wird in
 * accounts.remainder gespeichert und geht nie verloren; last_accrual wird nur um volle Sekunden
 * weitergeschoben, damit bei Neustarts nichts doppelt oder gar nicht ausgezahlt wird.
 */
public final class InvestService {

    public enum Result { OK, INSUFFICIENT, INVALID, PAUSED, TOO_LOW, TOO_HIGH, NO_ACCOUNT, ERROR }

    public record Entry(long id, long amount, long created) { }

    public record Info(long invested, long pending, BigDecimal perSecondCoins, boolean autocollect,
                       boolean paused, List<Entry> entries) { }

    private record Row(long pending, long remainder, long last, boolean auto, boolean paused) { }

    private static final BigInteger PPM = BigInteger.valueOf(1_000_000L);

    private final Database db;
    private final EconomyService eco;
    private final Settings s;
    private final LongSupplier clock;
    private long lastFlush;

    public InvestService(Database db, EconomyService eco, Settings settings, LongSupplier clock) {
        this.db = db;
        this.eco = eco;
        this.s = settings;
        this.clock = clock;
        this.lastFlush = clock.getAsLong();
    }

    // ------------------------------------------------------------- Investieren

    public Result invest(UUID id, long cents) {
        if (cents <= 0) {
            return Result.INVALID;
        }
        return db.tx(() -> {
            Row r = load(id);
            if (r == null) {
                return Result.NO_ACCOUNT;
            }
            if (s.paused || r.paused()) {
                return Result.PAUSED;
            }
            if (cents < s.minInvest) {
                return Result.TOO_LOW;
            }
            if (invested(id) + cents > s.maxTotalInvest) {
                return Result.TOO_HIGH;
            }
            // Bisherige Erträge zum alten Stand abrechnen, dann erst die neue Investition aufnehmen
            accrue(id, clock.getAsLong(), s.maxOnlineCatchupSeconds);
            EconomyService.Result w = eco.withdraw(id, cents, "INVEST", "player", "Investition");
            if (w == EconomyService.Result.INSUFFICIENT) {
                return Result.INSUFFICIENT;
            }
            if (w != EconomyService.Result.OK) {
                return Result.ERROR;
            }
            try {
                db.update("INSERT INTO investments(uuid,amount,created) VALUES(?,?,?)",
                        id.toString(), cents, clock.getAsLong());
            } catch (RuntimeException e) {
                if (!eco.isInternal()) {
                    eco.refundQuietly(id, cents);
                }
                throw e;
            }
            return Result.OK;
        });
    }

    public long invested(UUID id) {
        return db.scalar("SELECT COALESCE(SUM(amount),0) FROM investments WHERE uuid=?", id.toString());
    }

    public BigDecimal perSecondCoins(UUID id) {
        return BigDecimal.valueOf(invested(id)).multiply(BigDecimal.valueOf(s.ratePpm))
                .divide(BigDecimal.valueOf(1_000_000L)).movePointLeft(2);
    }

    // ------------------------------------------------------------------ Erträge

    /**
     * Rechnet Erträge bis {@code now} ab. Mehr als {@code maxSeconds} Sekunden werden nicht nachgebucht
     * (der Rest verfällt). @return die entstandenen Erträge in Cent.
     */
    public long accrue(UUID id, long now, long maxSeconds) {
        return db.tx(() -> {
            Row r = load(id);
            if (r == null) {
                return 0L;
            }
            long invested = invested(id);
            boolean frozen = s.paused || r.paused() || invested <= 0 || r.last() <= 0;
            long fullSec = Math.max(0L, (now - r.last()) / 1000L);
            long seconds;
            long newLast;
            if (frozen) {
                seconds = 0;
                newLast = now;
            } else if (fullSec > maxSeconds) {
                seconds = maxSeconds;
                newLast = now;
            } else {
                seconds = fullSec;
                newLast = r.last() + fullSec * 1000L;
            }
            long cents = 0;
            long rem = r.remainder();
            if (seconds > 0) {
                BigInteger num = BigInteger.valueOf(invested)
                        .multiply(BigInteger.valueOf(s.ratePpm))
                        .multiply(BigInteger.valueOf(seconds))
                        .add(BigInteger.valueOf(rem));
                BigInteger[] qr = num.divideAndRemainder(PPM);
                cents = qr[0].longValueExact();
                rem = qr[1].longValue();
            }
            if (cents == 0 && newLast == r.last() && rem == r.remainder()) {
                return 0L;
            }
            long pendingAdd = 0;
            long unloggedAdd = 0;
            if (cents > 0) {
                if (r.auto() && eco.rawDeposit(id, cents)) {
                    unloggedAdd = cents;
                } else {
                    pendingAdd = cents;
                }
            }
            db.update("UPDATE accounts SET pending=pending+?, unlogged=unlogged+?, remainder=?, last_accrual=? "
                    + "WHERE uuid=?", pendingAdd, unloggedAdd, rem, newLast, id.toString());
            return cents;
        });
    }

    /** Wird jede Sekunde für alle online Spieler aufgerufen (eine Transaktion für alle). */
    public void tickAll(Collection<UUID> online) {
        long now = clock.getAsLong();
        if (!online.isEmpty()) {
            Set<String> investors = new HashSet<>(db.query("SELECT DISTINCT uuid FROM investments",
                    rs -> rs.getString(1)));
            if (!investors.isEmpty()) {
                db.run(() -> {
                    for (UUID id : online) {
                        if (investors.contains(id.toString())) {
                            accrue(id, now, s.maxOnlineCatchupSeconds);
                        }
                    }
                });
            }
        }
        if (now - lastFlush >= s.payoutLogIntervalSeconds * 1000L) {
            lastFlush = now;
            flushAll();
        }
    }

    /** Beim Login: Offline-Zeit nach Konfiguration anrechnen. @return verdiente Cent. */
    public long onJoin(UUID id) {
        long cents = accrue(id, clock.getAsLong(), s.offlineEnabled ? s.maxOfflineSeconds : 0L);
        flush(id);
        return cents;
    }

    public void onQuit(UUID id) {
        accrue(id, clock.getAsLong(), s.maxOnlineCatchupSeconds);
        flush(id);
    }

    /** Schreibt gesammelte automatische Auszahlungen als EINEN Protokolleintrag. */
    public void flush(UUID id) {
        db.run(() -> {
            long u = db.scalar("SELECT unlogged FROM accounts WHERE uuid=?", id.toString());
            if (u > 0) {
                eco.log(id, "INVEST_PAYOUT", u, "system", "Rendite (zusammengefasst)");
                db.update("UPDATE accounts SET unlogged=0 WHERE uuid=?", id.toString());
            }
        });
    }

    public void flushAll() {
        for (String id : db.query("SELECT uuid FROM accounts WHERE unlogged>0", rs -> rs.getString(1))) {
            flush(UUID.fromString(id));
        }
    }

    // ----------------------------------------------------------- Abholen / Modus

    /** @return abgeholte Cent, 0 = nichts da, -1 = Fehler (z. B. Kontolimit). */
    public long collect(UUID id) {
        return db.tx(() -> {
            accrue(id, clock.getAsLong(), s.maxOnlineCatchupSeconds);
            Row r = load(id);
            if (r == null || r.pending() <= 0) {
                return 0L;
            }
            if (eco.deposit(id, r.pending(), "INVEST_COLLECT", "player", "Erträge abgeholt")
                    != EconomyService.Result.OK) {
                return -1L;
            }
            db.update("UPDATE accounts SET pending=0 WHERE uuid=?", id.toString());
            return r.pending();
        });
    }

    /** @return beim Einschalten sofort ausgezahlte Cent. */
    public long setAutocollect(UUID id, boolean on) {
        return db.tx(() -> {
            accrue(id, clock.getAsLong(), s.maxOnlineCatchupSeconds);
            db.update("UPDATE accounts SET autocollect=? WHERE uuid=?", on ? 1 : 0, id.toString());
            return on ? Math.max(0L, collect(id)) : 0L;
        });
    }

    public void setPlayerPaused(UUID id, boolean paused) {
        db.run(() -> {
            long now = clock.getAsLong();
            accrue(id, now, s.maxOnlineCatchupSeconds);
            db.update("UPDATE accounts SET paused=?, last_accrual=? WHERE uuid=?",
                    paused ? 1 : 0, now, id.toString());
        });
    }

    public Info info(UUID id) {
        return db.tx(() -> {
            accrue(id, clock.getAsLong(), s.maxOnlineCatchupSeconds);
            Row r = load(id);
            List<Entry> entries = db.query("SELECT id,amount,created FROM investments WHERE uuid=? ORDER BY id DESC",
                    rs -> new Entry(rs.getLong(1), rs.getLong(2), rs.getLong(3)), id.toString());
            if (r == null) {
                return new Info(0, 0, BigDecimal.ZERO, s.defaultAutocollect, false, entries);
            }
            return new Info(invested(id), r.pending(), perSecondCoins(id), r.auto(), r.paused(), entries);
        });
    }

    private Row load(UUID id) {
        return db.one("SELECT pending,remainder,last_accrual,autocollect,paused FROM accounts WHERE uuid=?",
                rs -> new Row(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getInt(4) == 1, rs.getInt(5) == 1),
                id.toString()).orElse(null);
    }
}
