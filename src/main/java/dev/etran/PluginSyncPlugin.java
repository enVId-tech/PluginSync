package dev.etran;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class PluginSyncPlugin extends JavaPlugin implements TabExecutor {

    private Syncer syncer;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        syncer = new Syncer(this);
        PluginCommand command = getCommand("pluginsync");
        if (command != null) {
            command.setExecutor(this);
            command.setTabCompleter(this);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("pluginsync.use")) {
            send(sender, ChatColor.RED, "You don't have permission to do that.");
            return true;
        }

        String sub = args.length == 0 ? "sync" : args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("scan")) {
            reloadConfig();
            Syncer.Plan plan = syncer.scan();
            if (!plan.hasChanges()) {
                send(sender, ChatColor.GREEN, "Nothing to do: the plugins folder matches what's loaded.");
            } else {
                if (!plan.toLoad.isEmpty()) {
                    List<String> names = new ArrayList<String>();
                    for (JarInfo info : plan.toLoad) {
                        names.add(info.name);
                    }
                    send(sender, ChatColor.AQUA, "Would load: " + join(names));
                }
                if (!plan.toUnload.isEmpty()) {
                    List<String> names = new ArrayList<String>();
                    for (Plugin p : plan.toUnload) {
                        names.add(p.getName());
                    }
                    send(sender, ChatColor.AQUA, "Would unload: " + join(names));
                }
                send(sender, ChatColor.GRAY, "Run /" + label + " to apply.");
            }
            for (Syncer.Line line : plan.notes) {
                send(sender, line.color, line.text);
            }
        } else if (sub.equals("sync")) {
            reloadConfig();
            Syncer.Plan plan = syncer.scan();
            if (!plan.hasChanges()) {
                send(sender, ChatColor.GREEN, "Nothing to do: the plugins folder matches what's loaded.");
                for (Syncer.Line line : plan.notes) {
                    send(sender, line.color, line.text);
                }
                return true;
            }
            for (Syncer.Line line : syncer.apply(plan)) {
                send(sender, line.color, line.text);
            }
        } else {
            send(sender, ChatColor.YELLOW, "Usage: /" + label + " [scan|sync]");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<String>();
        if (args.length == 1 && sender.hasPermission("pluginsync.use")) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            for (String s : Arrays.asList("scan", "sync")) {
                if (s.startsWith(prefix)) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    private void send(CommandSender sender, ChatColor color, String text) {
        sender.sendMessage(color + text);
        if (!(sender instanceof ConsoleCommandSender)) {
            getLogger().info("[" + sender.getName() + "] " + text);
        }
    }

    private static String join(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(parts.get(i));
        }
        return sb.toString();
    }
}
