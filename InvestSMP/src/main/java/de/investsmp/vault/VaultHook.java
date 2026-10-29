package de.investsmp.vault;

import de.investsmp.InvestSMP;
import de.investsmp.core.MoneyBackend;
import java.util.Optional;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;

/** Kapselt alle Vault-Klassenzugriffe, damit das Plugin auch ohne Vault startet. */
public final class VaultHook {
    private VaultHook() {
    }

    /** Liefert eine FREMDE Vault-Economy, falls registriert (unsere eigene wird ignoriert). */
    public static Optional<MoneyBackend> externalBackend(InvestSMP plugin) {
        RegisteredServiceProvider<Economy> rsp =
                plugin.getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null || rsp.getProvider() instanceof VaultEconomyProvider) {
            return Optional.empty();
        }
        return Optional.of(new VaultBackend(rsp.getProvider()));
    }

    /** Meldet die interne Economy bei Vault an – mit normaler Priorität, ohne andere zu überschreiben. */
    public static Object register(InvestSMP plugin) {
        VaultEconomyProvider provider = new VaultEconomyProvider(plugin);
        plugin.getServer().getServicesManager().register(Economy.class, provider, plugin, ServicePriority.Normal);
        return provider;
    }

    public static void unregister(InvestSMP plugin, Object provider) {
        if (provider instanceof VaultEconomyProvider p) {
            plugin.getServer().getServicesManager().unregister(Economy.class, p);
        }
    }
}
