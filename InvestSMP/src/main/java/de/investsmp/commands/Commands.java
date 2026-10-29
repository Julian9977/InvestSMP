package de.investsmp.commands;

import de.investsmp.Actions;
import de.investsmp.InvestSMP;
import de.investsmp.Messages;
import de.investsmp.util.Money;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

/** Verteilt /money /pay /baltop /invest /investsmp /dashboard. */
public final class Commands implements TabExecutor {
    private final InvestSMP plugin;

    public Commands(InvestSMP plugin) {
        this.plugin = plugin;
    }

    private Messages m() {
        return plugin.messages();
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        Actions act = plugin.actions();
        try {
            switch (cmd.getName().toLowerCase(Locale.ROOT)) {
                case "money" -> act.balance(s, a.length > 0 ? a[0] : null);
                case "pay" -> act.pay(s, a);
                case "baltop" -> act.baltop(s);
                case "invest" -> invest(s, a);
                case "investsmp" -> admin(s, a);
                case "dashboard" -> dashboard(s);
                default -> { return false; }
            }
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "Fehler bei /" + cmd.getName(), e);
            m().send(s, "internal-error");
        }
        return true;
    }

    private void invest(CommandSender s, String[] a) {
        if (!(s instanceof Player p)) {
            m().send(s, "player-only");
            return;
        }
        if (!p.hasPermission("investsmp.invest")) {
            m().send(p, "no-permission");
            return;
        }
        Actions act = plugin.actions();
        if (a.length == 0) {
            plugin.gui().openInvest(p);
            return;
        }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "add" -> {
                if (a.length != 2) {
                    m().send(p, "invest-usage");
                    return;
                }
                OptionalLong cents = Money.parse(a[1]);
                if (cents.isEmpty()) {
                    m().send(p, "invalid-amount");
                    return;
                }
                act.invest(p, cents.getAsLong());
            }
            case "info" -> act.info(p);
            case "collect" -> act.collect(p);
            case "autocollect" -> {
                Boolean on = null;
                if (a.length > 1) {
                    String v = a[1].toLowerCase(Locale.ROOT);
                    if (v.equals("on") || v.equals("an") || v.equals("true")) {
                        on = Boolean.TRUE;
                    } else if (v.equals("off") || v.equals("aus") || v.equals("false")) {
                        on = Boolean.FALSE;
                    } else {
                        m().send(p, "invest-usage");
                        return;
                    }
                }
                act.autocollect(p, on);
            }
            default -> m().send(p, "invest-usage");
        }
    }

    private void admin(CommandSender s, String[] a) {
        if (!s.hasPermission("investsmp.admin")) {
            m().send(s, "no-permission");
            return;
        }
        if (a.length > 0 && a[0].equalsIgnoreCase("reload")) {
            plugin.reloadAll();
            m().send(s, "admin-reloaded");
            return;
        }
        dashboard(s);
    }

    private void dashboard(CommandSender s) {
        if (!s.hasPermission("investsmp.admin")) {
            m().send(s, "no-permission");
            return;
        }
        if (!(s instanceof Player p)) {
            m().send(s, "player-only");
            return;
        }
        plugin.gui().openMain(p);
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        List<String> out = new ArrayList<>();
        switch (cmd.getName().toLowerCase(Locale.ROOT)) {
            case "money" -> {
                if (a.length == 1 && s.hasPermission("investsmp.money.others")) {
                    Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
                }
            }
            case "pay" -> {
                if (a.length == 1) {
                    Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
                }
            }
            case "invest" -> {
                if (a.length == 1) {
                    out.addAll(List.of("add", "info", "collect", "autocollect"));
                } else if (a.length == 2 && a[0].equalsIgnoreCase("autocollect")) {
                    out.addAll(List.of("on", "off"));
                }
            }
            case "investsmp" -> {
                if (a.length == 1 && s.hasPermission("investsmp.admin")) {
                    out.add("reload");
                }
            }
            default -> { }
        }
        String prefix = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(prefix));
        return out;
    }
}
