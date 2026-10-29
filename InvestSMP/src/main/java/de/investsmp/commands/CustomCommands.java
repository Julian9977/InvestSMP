package de.investsmp.commands;

import de.investsmp.InvestSMP;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

/** Eigene Befehle aus commands.yml (werden zur Laufzeit registriert und bei Reload ersetzt). */
public final class CustomCommands {
    private final InvestSMP plugin;
    private final Map<String, String> descriptions = new LinkedHashMap<>();

    public CustomCommands(InvestSMP plugin) {
        this.plugin = plugin;
    }

    public Map<String, String> describe() {
        return descriptions;
    }

    public void load() {
        unload();
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "commands.yml"));
        ConfigurationSection root = cfg.getConfigurationSection("commands");
        if (root == null) {
            return;
        }
        CommandMap map = Bukkit.getCommandMap();
        for (String name : root.getKeys(false)) {
            ConfigurationSection c = root.getConfigurationSection(name);
            String lower = name.toLowerCase(Locale.ROOT);
            if (c == null) {
                continue;
            }
            if (map.getKnownCommands().containsKey(lower)) {
                plugin.getLogger().warning("Eigener Befehl /" + name + " übersprungen: Name ist bereits vergeben.");
                continue;
            }
            Cmd cmd = new Cmd(lower, c);
            map.register("investsmp", cmd);
            descriptions.put(lower, cmd.getDescription());
        }
    }

    public void unload() {
        descriptions.clear();
        CommandMap map = Bukkit.getCommandMap();
        List<Command> mine = new ArrayList<>();
        for (Command c : map.getKnownCommands().values()) {
            if (c instanceof Cmd && !mine.contains(c)) {
                mine.add(c);
            }
        }
        map.getKnownCommands().values().removeIf(c -> c instanceof Cmd);
        mine.forEach(c -> c.unregister(map));
    }

    private final class Cmd extends Command {
        private final String permission;
        private final boolean asConsole;
        private final long cooldownMs;
        private final List<String> execute;
        private final List<String> message;
        private final Map<UUID, Long> last = new HashMap<>();

        Cmd(String name, ConfigurationSection c) {
            super(name, c.getString("description", "Eigener Befehl"), "/" + name, c.getStringList("aliases"));
            this.permission = c.getString("permission", "");
            this.asConsole = "console".equalsIgnoreCase(c.getString("run-as", "player"));
            this.cooldownMs = Math.max(0L, c.getLong("cooldown", 0L)) * 1000L;
            this.execute = c.getStringList("execute");
            this.message = c.getStringList("message");
        }

        @Override
        public boolean execute(CommandSender s, String label, String[] args) {
            if (!permission.isEmpty() && !s.hasPermission(permission)) {
                plugin.messages().send(s, "no-permission");
                return true;
            }
            if (cooldownMs > 0 && s instanceof Player p) {
                long now = System.currentTimeMillis();
                Long prev = last.get(p.getUniqueId());
                if (prev != null && now - prev < cooldownMs) {
                    plugin.messages().send(s, "custom-cooldown");
                    return true;
                }
                last.put(p.getUniqueId(), now);
            }
            for (String line : execute) {
                String cmd = fill(line, s, args, asConsole);
                if (cmd.startsWith("/")) {
                    cmd = cmd.substring(1);
                }
                if (!cmd.isBlank()) {
                    Bukkit.dispatchCommand(asConsole ? Bukkit.getConsoleSender() : s, cmd);
                }
            }
            for (String line : message) {
                plugin.messages().sendText(s, fill(line, s, args, false));
            }
            return true;
        }

        private String fill(String line, CommandSender s, String[] args, boolean sanitize) {
            String out = line.replace("{player}", s.getName());
            StringBuilder all = new StringBuilder();
            for (int i = 0; i < args.length; i++) {
                String a = sanitize ? args[i].replaceAll("[^A-Za-z0-9_.\\-]", "") : args[i];
                out = out.replace("{arg" + (i + 1) + "}", a);
                all.append(i > 0 ? " " : "").append(a);
            }
            out = out.replace("{args}", all.toString());
            return out.replaceAll("\\{arg\\d+}", "");
        }
    }
}
