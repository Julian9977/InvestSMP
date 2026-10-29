package de.investsmp;

import de.investsmp.core.EconomyService;
import de.investsmp.core.EconomyService.Account;
import de.investsmp.core.InvestService;
import de.investsmp.util.Money;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Gemeinsame Spieler-Aktionen für Befehle UND GUI (einheitliche Prüfungen und Nachrichten). */
public final class Actions {
    private final InvestSMP plugin;
    private final Map<UUID, Long> payCooldown = new HashMap<>();

    public Actions(InvestSMP plugin) {
        this.plugin = plugin;
    }

    private Messages m() {
        return plugin.messages();
    }

    public void balance(CommandSender s, String targetName) {
        EconomyService eco = plugin.economy();
        if (targetName == null) {
            if (!(s instanceof Player p)) {
                m().send(s, "player-only");
                return;
            }
            if (!s.hasPermission("investsmp.money")) {
                m().send(s, "no-permission");
                return;
            }
            m().send(s, "balance-own", "balance", Money.format(eco.balance(p.getUniqueId())));
            return;
        }
        if (!s.hasPermission("investsmp.money.others")) {
            m().send(s, "no-permission");
            return;
        }
        Optional<Account> t = eco.findByName(targetName);
        if (t.isEmpty()) {
            m().send(s, "player-not-found", "player", targetName);
            return;
        }
        m().send(s, "balance-other", "player", t.get().name(), "balance", Money.format(eco.balance(t.get().id())));
    }

    public void pay(CommandSender s, String[] a) {
        if (!(s instanceof Player p)) {
            m().send(s, "player-only");
            return;
        }
        if (!s.hasPermission("investsmp.pay")) {
            m().send(s, "no-permission");
            return;
        }
        if (a.length != 2) {
            m().send(s, "pay-usage");
            return;
        }
        OptionalLong amount = Money.parse(a[1]);
        if (amount.isEmpty()) {
            m().send(s, "invalid-amount");
            return;
        }
        EconomyService eco = plugin.economy();
        Optional<Account> target = eco.findByName(a[0]);
        if (target.isEmpty()) {
            m().send(s, "player-not-found", "player", a[0]);
            return;
        }
        long now = System.currentTimeMillis();
        long wait = plugin.getConfig().getLong("economy.pay-cooldown-ms", 1000L);
        Long last = payCooldown.get(p.getUniqueId());
        payCooldown.put(p.getUniqueId(), now);
        if (last != null && now - last < wait) {
            m().send(s, "pay-cooldown");
            return;
        }
        long cents = amount.getAsLong();
        EconomyService.Result r = eco.transfer(p.getUniqueId(), target.get().id(), cents, p.getName(),
                target.get().name());
        switch (r) {
            case OK -> {
                m().send(s, "pay-sent", "amount", Money.format(cents), "player", target.get().name());
                m().send(s, "balance-own", "balance", Money.format(eco.balance(p.getUniqueId())));
                Player tp = Bukkit.getPlayer(target.get().id());
                if (tp != null) {
                    m().send(tp, "pay-received", "amount", Money.format(cents), "player", p.getName());
                }
            }
            case INSUFFICIENT -> m().send(s, "insufficient-funds");
            case SAME_ACCOUNT -> m().send(s, "pay-self");
            case LIMIT -> m().send(s, "pay-limit");
            case INVALID -> m().send(s, "invalid-amount");
            default -> m().send(s, "internal-error");
        }
    }

    public void baltop(CommandSender s) {
        if (!s.hasPermission("investsmp.baltop")) {
            m().send(s, "no-permission");
            return;
        }
        List<EconomyService.Rich> top = plugin.economy().top(plugin.getConfig().getInt("economy.baltop-size", 10));
        if (top.isEmpty()) {
            m().send(s, "baltop-empty");
            return;
        }
        m().send(s, "baltop-header");
        int rank = 1;
        for (EconomyService.Rich r : top) {
            m().send(s, "baltop-line", "rank", rank++, "player", r.name(), "amount", Money.format(r.cents()));
        }
    }

    public void invest(Player p, long cents) {
        InvestService inv = plugin.invest();
        InvestService.Result r = inv.invest(p.getUniqueId(), cents);
        switch (r) {
            case OK -> {
                m().send(p, "invest-done", "amount", Money.format(cents));
                m().send(p, "invest-new-balance", "balance", Money.format(plugin.economy().balance(p.getUniqueId())));
                m().send(p, "invest-rate", "rate", Money.formatRate(inv.perSecondCoins(p.getUniqueId())));
            }
            case INSUFFICIENT -> m().send(p, "insufficient-funds");
            case INVALID -> m().send(p, "invalid-amount");
            case PAUSED -> m().send(p, "invest-paused");
            case TOO_LOW -> m().send(p, "invest-too-low", "min", Money.format(plugin.settings().minInvest));
            case TOO_HIGH -> m().send(p, "invest-too-high", "max", Money.format(plugin.settings().maxTotalInvest));
            default -> m().send(p, "internal-error");
        }
    }

    public void info(Player p) {
        InvestService.Info i = plugin.invest().info(p.getUniqueId());
        m().send(p, "info-header");
        m().send(p, "balance-own", "balance", Money.format(plugin.economy().balance(p.getUniqueId())));
        m().send(p, "info-invested", "amount", Money.format(i.invested()));
        m().send(p, "invest-rate", "rate", Money.formatRate(i.perSecondCoins()));
        m().send(p, "info-pending", "amount", Money.format(i.pending()));
        m().send(p, i.autocollect() ? "info-mode-auto" : "info-mode-manual");
        if (i.paused() || plugin.settings().paused) {
            m().send(p, "info-paused");
        }
        if (i.entries().isEmpty()) {
            m().send(p, "info-none");
            return;
        }
        int shown = 0;
        for (InvestService.Entry e : i.entries()) {
            if (shown++ >= 5) {
                break;
            }
            m().send(p, "info-entry", "id", e.id(), "amount", Money.format(e.amount()), "date", Money.date(e.created()));
        }
    }

    public void collect(Player p) {
        long c = plugin.invest().collect(p.getUniqueId());
        if (c > 0) {
            m().send(p, "collect-done", "amount", Money.format(c));
            m().send(p, "balance-own", "balance", Money.format(plugin.economy().balance(p.getUniqueId())));
        } else if (c == 0) {
            m().send(p, "collect-nothing");
        } else {
            m().send(p, "collect-failed");
        }
    }

    /** on == null schaltet um. */
    public void autocollect(Player p, Boolean on) {
        InvestService inv = plugin.invest();
        boolean target = on != null ? on : !inv.info(p.getUniqueId()).autocollect();
        long paidOut = inv.setAutocollect(p.getUniqueId(), target);
        m().send(p, target ? "autocollect-on" : "autocollect-off");
        if (paidOut > 0) {
            m().send(p, "collect-done", "amount", Money.format(paidOut));
        }
    }
}
