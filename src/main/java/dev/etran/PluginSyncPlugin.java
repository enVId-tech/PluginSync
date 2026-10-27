package dev.etran;

import org.bukkit.Bukkit;
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

    private static final List<String> SUBCOMMANDS = Arrays.asList("scan", "sync", "enable", "disable", "restart", "reload");
    private static final List<String> PLUGIN_ACTIONS = Arrays.asList("enable", "disable", "restart", "reload");

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
            scan(sender, label);
        } else if (sub.equals("sync")) {
            sync(sender);
        } else if (PLUGIN_ACTIONS.contains(sub)) {
            pluginAction(sender, label, sub, args);
        } else {
            usage(sender, label);
        }
        return true;
    }

    private void scan(CommandSender sender, String label) {
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
    }

    private void sync(CommandSender sender) {
        reloadConfig();
        Syncer.Plan plan = syncer.scan();
        if (!plan.hasChanges()) {
            send(sender, ChatColor.GREEN, "Nothing to do: the plugins folder matches what's loaded.");
            for (Syncer.Line line : plan.notes) {
                send(sender, line.color, line.text);
            }
            return;
        }
        for (Syncer.Line line : syncer.apply(plan)) {
            send(sender, line.color, line.text);
        }
    }

    /** /pluginsync enable|disable|restart|reload &lt;plugin&gt; [-f] */
    private void pluginAction(CommandSender sender, String label, String action, String[] args) {
        String name = null;
        boolean force = false;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equalsIgnoreCase("-f") || args[i].equalsIgnoreCase("--force")) {
                force = true;
            } else if (name == null) {
                name = args[i];
            }
        }
        if (name == null) {
            send(sender, ChatColor.YELLOW, "Usage: /" + label + " " + action + " <plugin> [-f]");
            return;
        }

        Plugin target = Bukkit.getPluginManager().getPlugin(name);
        if (target == null) {
            send(sender, ChatColor.RED, "No loaded plugin named " + name
                    + ". If you just added its jar, run /" + label + " first.");
            return;
        }
        if (target == this) {
            send(sender, ChatColor.RED, "PluginSync can't " + action + " itself.");
            return;
        }

        List<Syncer.Line> lines;
        if (action.equals("enable")) {
            lines = syncer.enable(target);
        } else if (action.equals("disable")) {
            lines = syncer.disable(target, force);
        } else if (action.equals("restart")) {
            lines = syncer.restart(target, force);
        } else {
            lines = syncer.reload(target, force);
        }
        for (Syncer.Line line : lines) {
            send(sender, line.color, line.text);
        }
    }

    private void usage(CommandSender sender, String label) {
        send(sender, ChatColor.YELLOW, "/" + label + "                     load new jars, unload removed ones");
        send(sender, ChatColor.YELLOW, "/" + label + " scan               preview what a sync would do");
        send(sender, ChatColor.YELLOW, "/" + label + " enable <plugin>    turn a disabled plugin on");
        send(sender, ChatColor.YELLOW, "/" + label + " disable <plugin>   turn a plugin off");
        send(sender, ChatColor.YELLOW, "/" + label + " restart <plugin>   disable, then enable");
        send(sender, ChatColor.YELLOW, "/" + label + " reload <plugin>    unload and load again from its jar");
        send(sender, ChatColor.GRAY, "Add -f to disable/restart/reload even if other plugins depend on it.");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<String>();
        if (!sender.hasPermission("pluginsync.use")) {
            return out;
        }
        if (args.length == 1) {
            addMatches(out, SUBCOMMANDS, args[0]);
        } else if (args.length == 2 && PLUGIN_ACTIONS.contains(args[0].toLowerCase(Locale.ROOT))) {
            List<String> names = new ArrayList<String>();
            for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
                if (p != this) {
                    names.add(p.getName());
                }
            }
            addMatches(out, names, args[1]);
        } else if (args.length == 3 && PLUGIN_ACTIONS.contains(args[0].toLowerCase(Locale.ROOT))) {
            addMatches(out, Arrays.asList("-f"), args[2]);
        }
        return out;
    }

    private static void addMatches(List<String> out, List<String> options, String typed) {
        String prefix = typed.toLowerCase(Locale.ROOT);
        for (String s : options) {
            if (s.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                out.add(s);
            }
        }
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
