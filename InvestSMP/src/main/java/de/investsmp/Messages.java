package de.investsmp;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/** Lädt messages.yml (mit eingebauten Defaults als Fallback). Platzhalter: {name}. Farben: &amp;-Codes. */
public final class Messages {
    private final JavaPlugin plugin;
    private YamlConfiguration cfg = new YamlConfiguration();

    public Messages(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        cfg = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "messages.yml"));
        try (InputStream in = plugin.getResource("messages.yml")) {
            if (in != null) {
                cfg.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
            }
        } catch (IOException ignored) {
            // Defaults sind optional
        }
    }

    /** Text ohne Präfix (für GUI-Beschriftungen). */
    public String raw(String key, Object... kv) {
        String s = cfg.getString(key);
        if (s == null) {
            return key;
        }
        for (int i = 0; i + 1 < kv.length; i += 2) {
            s = s.replace("{" + kv[i] + "}", String.valueOf(kv[i + 1]));
        }
        return s;
    }

    public List<String> list(String key) {
        return cfg.getStringList(key);
    }

    /** Sendet eine Nachricht mit dem Präfix [Invest SMP]. */
    public void send(CommandSender to, String key, Object... kv) {
        sendText(to, raw(key, kv));
    }

    public void sendText(CommandSender to, String text) {
        to.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(raw("prefix") + " " + text));
    }
}
