package de.investsmp;

import de.investsmp.core.Settings;
import de.investsmp.util.Money;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.UUID;
import java.util.logging.Level;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class Events implements Listener {
    private final InvestSMP plugin;

    public Events(InvestSMP plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        setup(e.getPlayer());
    }

    /** Konto anlegen/aktualisieren und Offline-Erträge anrechnen. */
    public void setup(Player p) {
        try {
            Settings s = plugin.settings();
            UUID id = p.getUniqueId();
            plugin.economy().ensureAccount(id, p.getName(), s.startingBalance, s.defaultAutocollect);
            long earned = plugin.invest().onJoin(id);
            if (earned > 0) {
                boolean auto = plugin.invest().info(id).autocollect();
                plugin.messages().send(p, auto ? "offline-earnings" : "offline-earnings-pending",
                        "amount", Money.format(earned));
            }
        } catch (RuntimeException ex) {
            plugin.getLogger().log(Level.SEVERE, "Konto von " + p.getName() + " konnte nicht geladen werden", ex);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        plugin.gui().clear(id);
        try {
            plugin.invest().onQuit(id);
        } catch (RuntimeException ex) {
            plugin.getLogger().log(Level.SEVERE, "Fehler beim Speichern von " + e.getPlayer().getName(), ex);
        }
    }

    /** Chat-Eingaben für GUI-Abfragen abfangen (Betrag eintippen). */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        if (!plugin.gui().hasPrompt(id)) {
            return;
        }
        e.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(e.message());
        Bukkit.getScheduler().runTask(plugin, () -> plugin.gui().complete(id, text));
    }
}
