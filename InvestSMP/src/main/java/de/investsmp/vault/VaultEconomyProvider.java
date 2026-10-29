package de.investsmp.vault;

import de.investsmp.InvestSMP;
import de.investsmp.core.EconomyService;
import de.investsmp.util.Money;
import java.util.List;
import java.util.UUID;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import net.milkbowl.vault.economy.EconomyResponse.ResponseType;
import org.bukkit.OfflinePlayer;

/** Stellt die interne Economy anderen Plugins über Vault bereit (nur wenn keine andere existiert). */
public final class VaultEconomyProvider implements Economy {
    private static final String NO_BANK = "Banken werden von InvestSMP nicht unterstützt";
    private final InvestSMP plugin;

    public VaultEconomyProvider(InvestSMP plugin) {
        this.plugin = plugin;
    }

    private EconomyService e() {
        return plugin.economy();
    }

    private static long cents(double amount) {
        return Double.isFinite(amount) ? Math.round(amount * 100.0) : -1L;
    }

    private UUID idOf(String name) {
        return e().findByName(name).map(EconomyService.Account::id).orElse(null);
    }

    private boolean ensure(OfflinePlayer p) {
        if (e().hasAccount(p.getUniqueId())) {
            return true;
        }
        if (p.getName() == null) {
            return false;
        }
        e().ensureAccount(p.getUniqueId(), p.getName(), plugin.settings().startingBalance,
                plugin.settings().defaultAutocollect);
        return e().hasAccount(p.getUniqueId());
    }

    private EconomyResponse change(UUID id, double amount, boolean add) {
        long c = cents(amount);
        if (id == null || c <= 0) {
            return new EconomyResponse(0, 0, ResponseType.FAILURE, "Ungültiger Betrag oder Konto");
        }
        EconomyService.Result r = add
                ? e().deposit(id, c, "VAULT_DEPOSIT", "Vault", "Plugin-Gutschrift")
                : e().withdraw(id, c, "VAULT_WITHDRAW", "Vault", "Plugin-Abbuchung");
        double bal = e().balance(id) / 100.0;
        return r == EconomyService.Result.OK
                ? new EconomyResponse(amount, bal, ResponseType.SUCCESS, null)
                : new EconomyResponse(0, bal, ResponseType.FAILURE, r.name());
    }

    @Override public boolean isEnabled() { return plugin.isEnabled(); }
    @Override public String getName() { return "InvestSMP"; }
    @Override public boolean hasBankSupport() { return false; }
    @Override public int fractionalDigits() { return 2; }
    @Override public String format(double amount) { return Money.format(cents(amount)) + " Coins"; }
    @Override public String currencyNamePlural() { return "Coins"; }
    @Override public String currencyNameSingular() { return "Coin"; }

    @Override public boolean hasAccount(String name) { return idOf(name) != null; }
    @Override public boolean hasAccount(OfflinePlayer p) { return e().hasAccount(p.getUniqueId()); }
    @Override public boolean hasAccount(String name, String world) { return hasAccount(name); }
    @Override public boolean hasAccount(OfflinePlayer p, String world) { return hasAccount(p); }

    @Override public double getBalance(String name) {
        UUID id = idOf(name);
        return id == null ? 0 : e().balance(id) / 100.0;
    }
    @Override public double getBalance(OfflinePlayer p) { return e().balance(p.getUniqueId()) / 100.0; }
    @Override public double getBalance(String name, String world) { return getBalance(name); }
    @Override public double getBalance(OfflinePlayer p, String world) { return getBalance(p); }

    @Override public boolean has(String name, double amount) { return getBalance(name) * 100.0 + 0.5 >= cents(amount); }
    @Override public boolean has(OfflinePlayer p, double amount) { return e().balance(p.getUniqueId()) >= cents(amount); }
    @Override public boolean has(String name, String world, double amount) { return has(name, amount); }
    @Override public boolean has(OfflinePlayer p, String world, double amount) { return has(p, amount); }

    @Override public EconomyResponse withdrawPlayer(String name, double amount) { return change(idOf(name), amount, false); }
    @Override public EconomyResponse withdrawPlayer(OfflinePlayer p, double amount) {
        return change(ensure(p) ? p.getUniqueId() : null, amount, false);
    }
    @Override public EconomyResponse withdrawPlayer(String name, String world, double amount) { return withdrawPlayer(name, amount); }
    @Override public EconomyResponse withdrawPlayer(OfflinePlayer p, String world, double amount) { return withdrawPlayer(p, amount); }

    @Override public EconomyResponse depositPlayer(String name, double amount) { return change(idOf(name), amount, true); }
    @Override public EconomyResponse depositPlayer(OfflinePlayer p, double amount) {
        return change(ensure(p) ? p.getUniqueId() : null, amount, true);
    }
    @Override public EconomyResponse depositPlayer(String name, String world, double amount) { return depositPlayer(name, amount); }
    @Override public EconomyResponse depositPlayer(OfflinePlayer p, String world, double amount) { return depositPlayer(p, amount); }

    @Override public boolean createPlayerAccount(String name) { return hasAccount(name); }
    @Override public boolean createPlayerAccount(OfflinePlayer p) { return ensure(p); }
    @Override public boolean createPlayerAccount(String name, String world) { return createPlayerAccount(name); }
    @Override public boolean createPlayerAccount(OfflinePlayer p, String world) { return ensure(p); }

    // Banken: nicht unterstützt
    private static EconomyResponse noBank() { return new EconomyResponse(0, 0, ResponseType.NOT_IMPLEMENTED, NO_BANK); }
    @Override public EconomyResponse createBank(String name, String player) { return noBank(); }
    @Override public EconomyResponse createBank(String name, OfflinePlayer player) { return noBank(); }
    @Override public EconomyResponse deleteBank(String name) { return noBank(); }
    @Override public EconomyResponse bankBalance(String name) { return noBank(); }
    @Override public EconomyResponse bankHas(String name, double amount) { return noBank(); }
    @Override public EconomyResponse bankWithdraw(String name, double amount) { return noBank(); }
    @Override public EconomyResponse bankDeposit(String name, double amount) { return noBank(); }
    @Override public EconomyResponse isBankOwner(String name, String player) { return noBank(); }
    @Override public EconomyResponse isBankOwner(String name, OfflinePlayer player) { return noBank(); }
    @Override public EconomyResponse isBankMember(String name, String player) { return noBank(); }
    @Override public EconomyResponse isBankMember(String name, OfflinePlayer player) { return noBank(); }
    @Override public List<String> getBanks() { return List.of(); }
}
