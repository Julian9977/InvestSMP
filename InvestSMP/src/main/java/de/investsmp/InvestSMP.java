package de.investsmp;

import de.investsmp.commands.Commands;
import de.investsmp.commands.CustomCommands;
import de.investsmp.core.EconomyService;
import de.investsmp.core.InternalBackend;
import de.investsmp.core.InvestService;
import de.investsmp.core.MoneyBackend;
import de.investsmp.core.Settings;
import de.investsmp.db.Database;
import de.investsmp.gui.GuiManager;
import de.investsmp.util.Money;
import de.investsmp.vault.VaultHook;
import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class InvestSMP extends JavaPlugin {
    private final Settings settings = new Settings();
    private Database db;
    private EconomyService economy;
    private InvestService invest;
    private Messages messages;
    private Actions actions;
    private GuiManager gui;
    private CustomCommands customCommands;
    private Events events;
    private Object vaultProvider;
    private BukkitTask ticker;
    private boolean tickErrorLogged;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        for (String f : List.of("messages.yml", "commands.yml")) {
            if (!new File(getDataFolder(), f).exists()) {
                saveResource(f, false);
            }
        }
        loadSettings();
        messages = new Messages(this);
        messages.load();

        try {
            db = Database.file(new File(getDataFolder(), "investsmp.db"));
        } catch (RuntimeException e) {
            getLogger().log(Level.SEVERE, "Datenbank konnte nicht geöffnet werden – Plugin wird deaktiviert", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        economy = new EconomyService(db, new InternalBackend(db), System::currentTimeMillis);
        invest = new InvestService(db, economy, settings, System::currentTimeMillis);
        actions = new Actions(this);
        gui = new GuiManager(this);
        events = new Events(this);
        customCommands = new CustomCommands(this);

        getServer().getPluginManager().registerEvents(events, this);
        getServer().getPluginManager().registerEvents(gui, this);
        Commands handler = new Commands(this);
        for (String name : List.of("money", "pay", "baltop", "invest", "investsmp", "dashboard")) {
            if (getCommand(name) != null) {
                getCommand(name).setExecutor(handler);
            }
        }
        customCommands.load();

        // Erst im ersten Tick: dann sind alle Economy-Plugins sicher registriert
        getServer().getScheduler().runTask(this, this::selectBackend);
        ticker = getServer().getScheduler().runTaskTimer(this, this::tick, 20L, 20L);
    }

    @Override
    public void onDisable() {
        if (ticker != null) {
            ticker.cancel();
        }
        if (invest != null) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                try {
                    invest.onQuit(p.getUniqueId());
                } catch (RuntimeException e) {
                    getLogger().log(Level.SEVERE, "Fehler beim Speichern von " + p.getName(), e);
                }
            }
            try {
                invest.flushAll();
            } catch (RuntimeException ignored) {
                // beim Beenden nicht kritisch
            }
        }
        if (vaultProvider != null) {
            VaultHook.unregister(this, vaultProvider);
        }
        if (customCommands != null) {
            customCommands.unload();
        }
        if (db != null) {
            db.close();
        }
    }

    /** Genau EIN System führt die Kontostände: fremde Vault-Economy oder unsere interne. */
    private void selectBackend() {
        String mode = getConfig().getString("economy.mode", "AUTO").toUpperCase(Locale.ROOT);
        boolean vault = getServer().getPluginManager().isPluginEnabled("Vault");
        if (vault && !mode.equals("INTERNAL")) {
            Optional<MoneyBackend> external = VaultHook.externalBackend(this);
            if (external.isPresent()) {
                economy.setBackend(external.get());
                getLogger().info("Nutze vorhandene Economy: " + external.get().name()
                        + " – InvestSMP führt KEINE eigenen Kontostände.");
                setupOnlinePlayers();
                return;
            }
            if (mode.equals("VAULT")) {
                getLogger().warning("Keine Vault-Economy gefunden – nutze die interne Economy.");
            }
        }
        if (vault && getConfig().getBoolean("economy.register-vault-provider", true)) {
            vaultProvider = VaultHook.register(this);
            getLogger().info("Interne Economy wurde bei Vault angemeldet.");
        }
        getLogger().info("Economy-Backend: " + economy.backendName());
        setupOnlinePlayers();
    }

    private void setupOnlinePlayers() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            events.setup(p);
        }
    }

    private void tick() {
        List<UUID> ids = Bukkit.getOnlinePlayers().stream().map(Player::getUniqueId).toList();
        try {
            invest.tickAll(ids);
        } catch (RuntimeException e) {
            if (!tickErrorLogged) {
                tickErrorLogged = true;
                getLogger().log(Level.SEVERE, "Fehler bei der Ertragsberechnung", e);
            }
        }
    }

    /** Erträge aller online Spieler zum alten Stand abrechnen (vor Rendite-Änderungen). */
    public void settleOnline() {
        tick();
    }

    public void loadSettings() {
        reloadConfig();
        FileConfiguration c = getConfig();
        settings.ratePpm = Math.max(0L, Math.min(1_000_000L, Math.round(c.getDouble("invest.rate-percent-per-second", 0.1) * 10000.0)));
        settings.minInvest = Math.max(1L, Money.coinsToCents(c.getDouble("invest.min-invest", 1)));
        settings.maxTotalInvest = Math.min(Money.MAX_BALANCE, Money.coinsToCents(c.getDouble("invest.max-total-per-player", 1_000_000)));
        settings.paused = c.getBoolean("invest.paused", false);
        settings.defaultAutocollect = c.getBoolean("invest.default-autocollect", true);
        settings.maxOnlineCatchupSeconds = Math.max(1L, c.getLong("invest.max-catchup-seconds", 60));
        settings.payoutLogIntervalSeconds = Math.max(10L, c.getLong("invest.payout-log-interval-seconds", 300));
        settings.offlineEnabled = c.getBoolean("invest.offline-earnings.enabled", true);
        settings.maxOfflineSeconds = Math.max(0L, c.getLong("invest.offline-earnings.max-seconds", 3600));
        settings.startingBalance = Math.max(0L, Money.coinsToCents(c.getDouble("economy.starting-balance", 0)));
    }

    public void saveSettings() {
        FileConfiguration c = getConfig();
        c.set("invest.rate-percent-per-second", settings.ratePpm / 10000.0);
        c.set("invest.min-invest", settings.minInvest / 100.0);
        c.set("invest.max-total-per-player", settings.maxTotalInvest / 100.0);
        c.set("invest.paused", settings.paused);
        c.set("invest.default-autocollect", settings.defaultAutocollect);
        c.set("invest.offline-earnings.enabled", settings.offlineEnabled);
        saveConfig();
    }

    public void reloadAll() {
        loadSettings();
        messages.load();
        customCommands.load();
    }

    public Settings settings() { return settings; }
    public EconomyService economy() { return economy; }
    public InvestService invest() { return invest; }
    public Messages messages() { return messages; }
    public Actions actions() { return actions; }
    public GuiManager gui() { return gui; }
    public CustomCommands customCommands() { return customCommands; }
}
