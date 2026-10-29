package de.investsmp.gui;

import de.investsmp.InvestSMP;
import de.investsmp.Messages;
import de.investsmp.core.EconomyService;
import de.investsmp.core.EconomyService.Account;
import de.investsmp.core.InvestService;
import de.investsmp.core.Settings;
import de.investsmp.util.Money;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

/** Spieler-Menü (/invest) und Admin-Dashboard (/investsmp, /dashboard). Alle Texte stammen aus messages.yml. */
public final class GuiManager implements Listener {

    private enum Type { INVEST, MAIN, PLAYERS, PLAYER, TX, SETTINGS, COMMANDS }

    private static final class Holder implements InventoryHolder {
        final Type type;
        final UUID target;
        final int page;
        List<Account> list = List.of();
        Inventory inv;

        Holder(Type type, UUID target, int page) {
            this.type = type;
            this.target = target;
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private record Prompt(Consumer<String> handler, boolean admin) { }

    private final InvestSMP plugin;
    private final Map<UUID, Prompt> prompts = new HashMap<>();

    public GuiManager(InvestSMP plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ Helfer

    private Messages m() {
        return plugin.messages();
    }

    private String t(String key, Object... kv) {
        return m().raw(key, kv);
    }

    private Component c(String s) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(s).decoration(TextDecoration.ITALIC, false);
    }

    private Inventory create(Holder h, int size, String title) {
        Inventory inv = Bukkit.createInventory(h, size, LegacyComponentSerializer.legacyAmpersand().deserialize(title));
        h.inv = inv;
        return inv;
    }

    private ItemStack item(Material mat, String name, String... lore) {
        return item(mat, name, Arrays.asList(lore));
    }

    private ItemStack item(Material mat, String name, List<String> lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(c(name));
        if (!lore.isEmpty()) {
            meta.lore(lore.stream().map(this::c).toList());
        }
        it.setItemMeta(meta);
        return it;
    }

    private ItemStack head(UUID id, String name, List<String> lore) {
        ItemStack it = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) it.getItemMeta();
        meta.setOwningPlayer(Bukkit.getOfflinePlayer(id));
        meta.displayName(c(name));
        meta.lore(lore.stream().map(this::c).toList());
        it.setItemMeta(meta);
        return it;
    }

    private void fill(Inventory inv) {
        ItemStack glass = item(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < inv.getSize(); i++) {
            if (inv.getItem(i) == null) {
                inv.setItem(i, glass);
            }
        }
    }

    private void later(Runnable r) {
        Bukkit.getScheduler().runTask(plugin, r);
    }

    private List<Long> steps() {
        List<Long> l = plugin.getConfig().getLongList("gui.admin-steps");
        return l.size() >= 3 ? l : List.of(100L, 1000L, 10000L);
    }

    private static OptionalLong parseAllowZero(String txt) {
        return txt.trim().equals("0") ? OptionalLong.of(0L) : Money.parse(txt);
    }

    // ---------------------------------------------------------- Chat-Abfragen

    private void prompt(Player p, String key, boolean admin, Consumer<String> handler) {
        prompts.put(p.getUniqueId(), new Prompt(handler, admin));
        later(p::closeInventory);
        m().send(p, key);
        m().send(p, "gui.prompt.cancel-hint");
    }

    public boolean hasPrompt(UUID id) {
        return prompts.containsKey(id);
    }

    public void clear(UUID id) {
        prompts.remove(id);
    }

    public void complete(UUID id, String text) {
        Prompt pr = prompts.remove(id);
        Player p = Bukkit.getPlayer(id);
        if (pr == null || p == null) {
            return;
        }
        if (text.trim().equalsIgnoreCase("abbrechen")) {
            m().send(p, "gui.prompt.cancelled");
            return;
        }
        if (pr.admin() && !p.hasPermission("investsmp.admin")) {
            m().send(p, "no-permission");
            return;
        }
        try {
            pr.handler().accept(text.trim());
        } catch (RuntimeException ex) {
            plugin.getLogger().log(Level.SEVERE, "Fehler bei GUI-Eingabe", ex);
            m().send(p, "internal-error");
        }
    }

    // ------------------------------------------------------- Spieler: /invest

    public void openInvest(Player p) {
        UUID id = p.getUniqueId();
        InvestService.Info i = plugin.invest().info(id);
        Holder h = new Holder(Type.INVEST, id, 0);
        Inventory inv = create(h, 27, t("gui.invest.title"));
        inv.setItem(4, item(Material.BOOK, t("gui.invest.info"),
                t("gui.invest.l-balance", "amount", Money.format(plugin.economy().balance(id))),
                t("gui.invest.l-invested", "amount", Money.format(i.invested())),
                t("gui.invest.l-rate", "rate", Money.formatRate(i.perSecondCoins())),
                t("gui.invest.l-pending", "amount", Money.format(i.pending()))));
        List<Long> presets = plugin.getConfig().getLongList("gui.presets");
        for (int k = 0; k < Math.min(3, presets.size()); k++) {
            inv.setItem(10 + k, item(Material.GOLD_INGOT,
                    t("gui.invest.preset", "amount", Money.format(presets.get(k) * 100L))));
        }
        inv.setItem(13, item(Material.PAPER, t("gui.invest.custom")));
        inv.setItem(15, item(Material.CHEST, t("gui.invest.collect"),
                t("gui.invest.l-pending", "amount", Money.format(i.pending()))));
        inv.setItem(16, item(i.autocollect() ? Material.LIME_DYE : Material.GRAY_DYE,
                t("gui.invest.autocollect", "state", i.autocollect() ? t("gui.state-on") : t("gui.state-off"))));
        inv.setItem(22, item(Material.BARRIER, t("gui.close")));
        fill(inv);
        p.openInventory(inv);
    }

    private void clickInvest(Player p, int slot) {
        List<Long> presets = plugin.getConfig().getLongList("gui.presets");
        if (slot >= 10 && slot <= 12 && slot - 10 < presets.size()) {
            plugin.actions().invest(p, presets.get(slot - 10) * 100L);
            later(() -> openInvest(p));
            return;
        }
        switch (slot) {
            case 13 -> prompt(p, "gui.prompt.invest", false, txt -> {
                OptionalLong cents = Money.parse(txt);
                if (cents.isPresent()) {
                    plugin.actions().invest(p, cents.getAsLong());
                } else {
                    m().send(p, "invalid-amount");
                }
                openInvest(p);
            });
            case 15 -> {
                plugin.actions().collect(p);
                later(() -> openInvest(p));
            }
            case 16 -> {
                plugin.actions().autocollect(p, null);
                later(() -> openInvest(p));
            }
            case 22 -> later(p::closeInventory);
            default -> { }
        }
    }

    // ------------------------------------------------------------- Dashboard

    public void openMain(Player p) {
        Holder h = new Holder(Type.MAIN, null, 0);
        Inventory inv = create(h, 27, t("gui.main.title"));
        EconomyService.Stats st = plugin.economy().stats();
        Settings s = plugin.settings();
        inv.setItem(10, item(Material.PLAYER_HEAD, t("gui.main.players"), t("gui.main.players-lore")));
        inv.setItem(11, item(Material.BOOK, t("gui.main.tx"), t("gui.main.tx-lore")));
        inv.setItem(12, item(Material.COMPARATOR, t("gui.main.settings"), t("gui.main.settings-lore")));
        inv.setItem(13, item(s.paused ? Material.RED_CONCRETE : Material.LIME_CONCRETE,
                t("gui.main.pause", "state", s.paused ? t("gui.paused") : t("gui.running")), t("gui.main.pause-lore")));
        inv.setItem(14, item(Material.COMMAND_BLOCK, t("gui.main.commands"), t("gui.main.commands-lore")));
        inv.setItem(15, item(Material.NETHER_STAR, t("gui.main.reload"), t("gui.main.reload-lore")));
        inv.setItem(16, item(Material.GOLD_BLOCK, t("gui.main.stats"),
                t("gui.main.stats-money", "amount", Money.format(st.totalMoney())),
                t("gui.main.stats-invested", "amount", Money.format(st.totalInvested())),
                t("gui.main.stats-accounts", "count", st.accounts()),
                t("gui.main.stats-rate", "rate", s.ratePercentString()),
                t("gui.main.stats-backend", "name", plugin.economy().backendName())));
        inv.setItem(22, item(Material.BARRIER, t("gui.close")));
        fill(inv);
        p.openInventory(inv);
    }

    private void clickMain(Player p, int slot) {
        Settings s = plugin.settings();
        switch (slot) {
            case 10 -> later(() -> openPlayers(p, 0));
            case 11 -> later(() -> openTx(p, null));
            case 12 -> later(() -> openSettings(p));
            case 13 -> {
                plugin.settleOnline();
                s.paused = !s.paused;
                plugin.saveSettings();
                later(() -> openMain(p));
            }
            case 14 -> later(() -> openCommands(p));
            case 15 -> {
                plugin.reloadAll();
                m().send(p, "admin-reloaded");
                later(() -> openMain(p));
            }
            case 22 -> later(p::closeInventory);
            default -> { }
        }
    }

    // --------------------------------------------------------- Spielerliste

    public void openPlayers(Player p, int page) {
        List<Account> all = plugin.economy().accounts();
        int maxPage = Math.max(0, (all.size() - 1) / 45);
        int pg = Math.min(Math.max(page, 0), maxPage);
        Holder h = new Holder(Type.PLAYERS, null, pg);
        h.list = all;
        Inventory inv = create(h, 54, t("gui.players.title", "page", pg + 1, "pages", maxPage + 1));
        for (int i = 0; i < 45; i++) {
            int idx = pg * 45 + i;
            if (idx >= all.size()) {
                break;
            }
            Account a = all.get(idx);
            inv.setItem(i, head(a.id(), "&e" + a.name(), List.of(
                    t("gui.players.l-balance", "amount", Money.format(plugin.economy().balance(a.id()))),
                    t("gui.players.l-click"))));
        }
        if (pg > 0) {
            inv.setItem(45, item(Material.ARROW, t("gui.prev")));
        }
        if (pg < maxPage) {
            inv.setItem(53, item(Material.ARROW, t("gui.next")));
        }
        inv.setItem(49, item(Material.BARRIER, t("gui.back")));
        fill(inv);
        p.openInventory(inv);
    }

    private void clickPlayers(Player p, Holder h, int slot) {
        if (slot == 45 && h.page > 0) {
            later(() -> openPlayers(p, h.page - 1));
        } else if (slot == 53) {
            later(() -> openPlayers(p, h.page + 1));
        } else if (slot == 49) {
            later(() -> openMain(p));
        } else if (slot >= 0 && slot < 45) {
            int idx = h.page * 45 + slot;
            if (idx < h.list.size()) {
                UUID target = h.list.get(idx).id();
                later(() -> openPlayer(p, target));
            }
        }
    }

    // ------------------------------------------------------ Spieler verwalten

    public void openPlayer(Player admin, UUID target) {
        EconomyService eco = plugin.economy();
        InvestService.Info i = plugin.invest().info(target);
        String name = eco.nameOf(target);
        Holder h = new Holder(Type.PLAYER, target, 0);
        Inventory inv = create(h, 36, t("gui.player.title", "player", name));
        inv.setItem(4, head(target, "&e" + name, List.of(
                t("gui.player.l-balance", "amount", Money.format(eco.balance(target))),
                t("gui.player.l-invested", "amount", Money.format(i.invested())),
                t("gui.player.l-pending", "amount", Money.format(i.pending())),
                t("gui.player.l-rate", "rate", Money.formatRate(i.perSecondCoins())),
                t("gui.player.l-mode", "mode", i.autocollect() ? t("gui.player.mode-auto") : t("gui.player.mode-manual")),
                i.paused() ? t("gui.player.l-paused") : t("gui.player.l-active"))));
        List<Long> st = steps();
        for (int k = 0; k < 3; k++) {
            inv.setItem(10 + k, item(Material.EMERALD, t("gui.player.add", "amount", Money.format(st.get(k) * 100L))));
            inv.setItem(19 + k, item(Material.REDSTONE, t("gui.player.remove", "amount", Money.format(st.get(k) * 100L))));
        }
        List<String> lore = new ArrayList<>();
        lore.add(t("gui.player.l-total", "amount", Money.format(i.invested()), "count", i.entries().size()));
        int shown = 0;
        for (InvestService.Entry e : i.entries()) {
            if (shown++ >= 10) {
                break;
            }
            lore.add(t("gui.player.l-entry", "id", e.id(), "amount", Money.format(e.amount()), "date", Money.date(e.created())));
        }
        if (i.entries().isEmpty()) {
            lore.add(t("gui.player.l-none"));
        }
        inv.setItem(13, item(Material.BOOK, t("gui.player.invest-book"), lore));
        inv.setItem(14, item(Material.PAPER, t("gui.player.add-custom")));
        inv.setItem(23, item(Material.PAPER, t("gui.player.remove-custom")));
        inv.setItem(25, item(Material.NAME_TAG, t("gui.player.set")));
        inv.setItem(16, item(i.paused() ? Material.RED_CONCRETE : Material.LIME_CONCRETE,
                t("gui.player.pause", "state", i.paused() ? t("gui.paused") : t("gui.running"))));
        inv.setItem(22, item(Material.WRITABLE_BOOK, t("gui.player.tx")));
        inv.setItem(31, item(Material.BARRIER, t("gui.back")));
        fill(inv);
        admin.openInventory(inv);
    }

    private void adminChange(Player admin, UUID target, long cents, boolean add) {
        EconomyService eco = plugin.economy();
        EconomyService.Result r = add
                ? eco.deposit(target, cents, "ADMIN_ADD", admin.getName(), "Admin-GUI")
                : eco.withdraw(target, cents, "ADMIN_REMOVE", admin.getName(), "Admin-GUI");
        String name = eco.nameOf(target);
        if (r == EconomyService.Result.OK) {
            m().send(admin, add ? "admin-added" : "admin-removed", "amount", Money.format(cents),
                    "player", name, "balance", Money.format(eco.balance(target)));
            plugin.getLogger().info("[ADMIN] " + admin.getName() + (add ? " +" : " -") + Money.format(cents)
                    + " Coins bei " + name);
        } else {
            m().send(admin, r == EconomyService.Result.INSUFFICIENT ? "admin-insufficient" : "admin-failed");
        }
    }

    private void clickPlayer(Player p, Holder h, int slot) {
        UUID t = h.target;
        List<Long> st = steps();
        if (slot >= 10 && slot <= 12) {
            adminChange(p, t, st.get(slot - 10) * 100L, true);
            later(() -> openPlayer(p, t));
        } else if (slot >= 19 && slot <= 21) {
            adminChange(p, t, st.get(slot - 19) * 100L, false);
            later(() -> openPlayer(p, t));
        } else {
            switch (slot) {
                case 14 -> prompt(p, "gui.prompt.add", true, txt -> {
                    Money.parse(txt).ifPresentOrElse(cents -> adminChange(p, t, cents, true),
                            () -> m().send(p, "invalid-amount"));
                    openPlayer(p, t);
                });
                case 23 -> prompt(p, "gui.prompt.remove", true, txt -> {
                    Money.parse(txt).ifPresentOrElse(cents -> adminChange(p, t, cents, false),
                            () -> m().send(p, "invalid-amount"));
                    openPlayer(p, t);
                });
                case 25 -> prompt(p, "gui.prompt.set", true, txt -> {
                    OptionalLong cents = parseAllowZero(txt);
                    if (cents.isEmpty()) {
                        m().send(p, "invalid-amount");
                    } else {
                        EconomyService.Result r = plugin.economy().setBalance(t, cents.getAsLong(), p.getName(), "Admin-GUI");
                        m().send(p, r == EconomyService.Result.OK ? "admin-set" : "admin-failed",
                                "player", plugin.economy().nameOf(t), "balance", Money.format(plugin.economy().balance(t)));
                        plugin.getLogger().info("[ADMIN] " + p.getName() + " setzte Kontostand von "
                                + plugin.economy().nameOf(t) + " auf " + Money.format(cents.getAsLong()));
                    }
                    openPlayer(p, t);
                });
                case 16 -> {
                    plugin.invest().setPlayerPaused(t, !plugin.invest().info(t).paused());
                    later(() -> openPlayer(p, t));
                }
                case 22 -> later(() -> openTx(p, t));
                case 31 -> later(() -> openPlayers(p, 0));
                default -> { }
            }
        }
    }

    // ------------------------------------------------------ Transaktionen

    public void openTx(Player p, UUID target) {
        Holder h = new Holder(Type.TX, target, 0);
        String title = target == null ? t("gui.tx.title") : t("gui.tx.title-player", "player", plugin.economy().nameOf(target));
        Inventory inv = create(h, 54, title);
        int slot = 0;
        for (EconomyService.TxEntry e : plugin.economy().history(target, 45)) {
            String amt = (e.amount() >= 0 ? "&a+" : "&c-") + Money.format(Math.abs(e.amount()));
            inv.setItem(slot++, item(e.amount() >= 0 ? Material.PAPER : Material.MAP,
                    "&e" + e.type() + " " + amt,
                    t("gui.tx.l-player", "player", e.name()),
                    t("gui.tx.l-balance", "amount", Money.format(e.balanceAfter())),
                    t("gui.tx.l-actor", "actor", e.actor()),
                    t("gui.tx.l-note", "note", e.note() == null ? "" : e.note()),
                    t("gui.tx.l-date", "date", Money.date(e.ts()))));
        }
        inv.setItem(49, item(Material.BARRIER, t("gui.back")));
        fill(inv);
        p.openInventory(inv);
    }

    // ---------------------------------------------------------- Einstellungen

    public void openSettings(Player p) {
        Settings s = plugin.settings();
        Holder h = new Holder(Type.SETTINGS, null, 0);
        Inventory inv = create(h, 27, t("gui.settings.title"));
        inv.setItem(10, item(Material.RED_CONCRETE, t("gui.settings.rate-down")));
        inv.setItem(11, item(Material.CLOCK, t("gui.settings.rate", "rate", s.ratePercentString()), t("gui.settings.rate-lore")));
        inv.setItem(12, item(Material.LIME_CONCRETE, t("gui.settings.rate-up")));
        inv.setItem(14, item(Material.GOLD_NUGGET, t("gui.settings.min", "amount", Money.format(s.minInvest)), t("gui.settings.click-set")));
        inv.setItem(15, item(Material.GOLD_BLOCK, t("gui.settings.max", "amount", Money.format(s.maxTotalInvest)), t("gui.settings.click-set")));
        inv.setItem(16, item(Material.ENDER_PEARL, t("gui.settings.offline", "state", s.offlineEnabled ? t("gui.state-on") : t("gui.state-off")),
                t("gui.settings.offline-lore", "seconds", s.maxOfflineSeconds)));
        inv.setItem(20, item(Material.LEVER, t("gui.settings.autocollect", "state", s.defaultAutocollect ? t("gui.state-on") : t("gui.state-off")),
                t("gui.settings.autocollect-lore")));
        inv.setItem(22, item(Material.BARRIER, t("gui.back")));
        fill(inv);
        p.openInventory(inv);
    }

    private void clickSettings(Player p, int slot) {
        Settings s = plugin.settings();
        switch (slot) {
            case 10 -> {
                plugin.settleOnline();
                s.ratePpm = Math.max(0L, s.ratePpm - 100L);
                plugin.saveSettings();
                later(() -> openSettings(p));
            }
            case 12 -> {
                plugin.settleOnline();
                s.ratePpm = Math.min(1_000_000L, s.ratePpm + 100L);
                plugin.saveSettings();
                later(() -> openSettings(p));
            }
            case 11 -> prompt(p, "gui.prompt.rate", true, txt -> {
                try {
                    double v = Double.parseDouble(txt.replace(',', '.'));
                    if (!(v >= 0 && v <= 100)) {
                        throw new NumberFormatException();
                    }
                    plugin.settleOnline();
                    s.ratePpm = Math.round(v * 10000.0);
                    plugin.saveSettings();
                } catch (NumberFormatException ex) {
                    m().send(p, "gui.prompt.invalid");
                }
                openSettings(p);
            });
            case 14 -> prompt(p, "gui.prompt.min", true, txt -> {
                OptionalLong c = Money.parse(txt);
                if (c.isPresent()) {
                    s.minInvest = c.getAsLong();
                    plugin.saveSettings();
                } else {
                    m().send(p, "invalid-amount");
                }
                openSettings(p);
            });
            case 15 -> prompt(p, "gui.prompt.max", true, txt -> {
                OptionalLong c = Money.parse(txt);
                if (c.isPresent()) {
                    s.maxTotalInvest = c.getAsLong();
                    plugin.saveSettings();
                } else {
                    m().send(p, "invalid-amount");
                }
                openSettings(p);
            });
            case 16 -> {
                s.offlineEnabled = !s.offlineEnabled;
                plugin.saveSettings();
                later(() -> openSettings(p));
            }
            case 20 -> {
                s.defaultAutocollect = !s.defaultAutocollect;
                plugin.saveSettings();
                later(() -> openSettings(p));
            }
            case 22 -> later(() -> openMain(p));
            default -> { }
        }
    }

    // ------------------------------------------------------- Custom Commands

    public void openCommands(Player p) {
        Holder h = new Holder(Type.COMMANDS, null, 0);
        Inventory inv = create(h, 54, t("gui.commands.title"));
        int slot = 0;
        for (Map.Entry<String, String> e : plugin.customCommands().describe().entrySet()) {
            if (slot >= 45) {
                break;
            }
            inv.setItem(slot++, item(Material.COMMAND_BLOCK, "&e/" + e.getKey(), "&7" + e.getValue()));
        }
        if (slot == 0) {
            inv.setItem(22, item(Material.BARRIER, t("gui.commands.none")));
        }
        inv.setItem(49, item(Material.BARRIER, t("gui.back")));
        fill(inv);
        p.openInventory(inv);
    }

    // ------------------------------------------------------------- Events

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder h)) {
            return;
        }
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) {
            return;
        }
        if (e.getClickedInventory() == null || e.getClickedInventory().getHolder() != h) {
            return;
        }
        if (h.type != Type.INVEST && !p.hasPermission("investsmp.admin")) {
            later(p::closeInventory);
            m().send(p, "no-permission");
            return;
        }
        int slot = e.getSlot();
        try {
            switch (h.type) {
                case INVEST -> clickInvest(p, slot);
                case MAIN -> clickMain(p, slot);
                case PLAYERS -> clickPlayers(p, h, slot);
                case PLAYER -> clickPlayer(p, h, slot);
                case TX -> {
                    if (slot == 49) {
                        later(() -> {
                            if (h.target != null) {
                                openPlayer(p, h.target);
                            } else {
                                openMain(p);
                            }
                        });
                    }
                }
                case SETTINGS -> clickSettings(p, slot);
                case COMMANDS -> {
                    if (slot == 49) {
                        later(() -> openMain(p));
                    }
                }
            }
        } catch (RuntimeException ex) {
            plugin.getLogger().log(Level.SEVERE, "Fehler im GUI", ex);
            later(p::closeInventory);
            m().send(p, "internal-error");
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Holder) {
            e.setCancelled(true);
        }
    }
}
