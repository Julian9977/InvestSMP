package de.investsmp.core;

import de.investsmp.db.Database;
import de.investsmp.util.Money;
import java.util.UUID;

public final class InternalBackend implements MoneyBackend {
    private final Database db;

    public InternalBackend(Database db) {
        this.db = db;
    }

    @Override
    public String name() {
        return "InvestSMP (intern, SQLite)";
    }

    @Override
    public boolean isInternal() {
        return true;
    }

    @Override
    public boolean hasAccount(UUID id) {
        return db.scalar("SELECT COUNT(*) FROM accounts WHERE uuid=?", id.toString()) > 0;
    }

    @Override
    public void createAccount(UUID id, String name) {
        // Konto wird von EconomyService#ensureAccount angelegt
    }

    @Override
    public long balance(UUID id) {
        return db.scalar("SELECT balance FROM accounts WHERE uuid=?", id.toString());
    }

    @Override
    public boolean withdraw(UUID id, long cents) {
        // Bedingtes UPDATE: nie negativ, auch nicht bei parallelen Aufrufen
        return db.update("UPDATE accounts SET balance=balance-? WHERE uuid=? AND balance>=?",
                cents, id.toString(), cents) == 1;
    }

    @Override
    public boolean deposit(UUID id, long cents) {
        return db.update("UPDATE accounts SET balance=balance+? WHERE uuid=? AND balance<=?",
                cents, id.toString(), Money.MAX_BALANCE - cents) == 1;
    }
}
