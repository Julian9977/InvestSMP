package de.investsmp.vault;

import de.investsmp.core.MoneyBackend;
import java.util.UUID;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

/** Nutzt eine bereits installierte Vault-Economy als einziges Konto-System. */
public final class VaultBackend implements MoneyBackend {
    private final Economy eco;

    public VaultBackend(Economy eco) {
        this.eco = eco;
    }

    private OfflinePlayer op(UUID id) {
        return Bukkit.getOfflinePlayer(id);
    }

    @Override
    public String name() {
        return eco.getName() + " (via Vault)";
    }

    @Override
    public boolean isInternal() {
        return false;
    }

    @Override
    public boolean hasAccount(UUID id) {
        return eco.hasAccount(op(id));
    }

    @Override
    public void createAccount(UUID id, String name) {
        eco.createPlayerAccount(op(id));
    }

    @Override
    public long balance(UUID id) {
        return Math.round(eco.getBalance(op(id)) * 100.0);
    }

    @Override
    public boolean withdraw(UUID id, long cents) {
        double amount = cents / 100.0;
        OfflinePlayer p = op(id);
        return eco.has(p, amount) && eco.withdrawPlayer(p, amount).transactionSuccess();
    }

    @Override
    public boolean deposit(UUID id, long cents) {
        return eco.depositPlayer(op(id), cents / 100.0).transactionSuccess();
    }
}
