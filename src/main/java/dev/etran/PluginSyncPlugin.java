package dev.etran;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public final class PluginSyncPlugin extends JavaPlugin implements TabExecutor {

    private static final List<String> SUBCOMMANDS =
            Arrays.asList("scan", "sync", "choose", "update", "enable", "disable", "restart", "reload");
    private static final List<String> PLUGIN_ACTIONS = Arrays.asList("enable", "disable", "restart", "reload");

    private Syncer syncer;
    private ChoiceMenu menu;

    @Override
    public void onEnable() {
        // Load every class of this jar now. Self-update replaces this plugin while its own code is
        // running, and the jar on disk may already have been overwritten by then; classes that are
        // already in memory keep working either way.
        preloadOwnClasses();
        saveDefaultConfig();
        syncer = new Syncer(this);
        menu = new ChoiceMenu(this);
        getServer().getPluginManager().registerEvents(menu, this);
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
            sync(sender, label);
        } else if (sub.equals("choose")) {
            choose(sender, label, args);
        } else if (sub.equals("update")) {
            for (Syncer.Line line : syncer.updateSelf()) {
                send(sender, line);
            }
        } else if (PLUGIN_ACTIONS.contains(sub)) {
            pluginAction(sender, label, sub, args);
        } else {
            usage(sender, label);
        }
        return true;
    }

    // ------------------------------------------------------------ scan / sync

    private void scan(CommandSender sender, String label) {
        reloadConfig();
        Syncer.Plan plan = syncer.scan();
        if (!plan.hasChanges()) {
            send(sender, ChatColor.GREEN, "Nothing to load or unload: the plugins folder matches what's loaded.");
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
        printConflicts(sender, label, plan);
        for (Syncer.Line line : plan.notes) {
            send(sender, line);
        }
    }

    private void sync(CommandSender sender, String label) {
        reloadConfig();
        Syncer.Plan plan = syncer.scan();
        if (!plan.hasChanges()) {
            send(sender, ChatColor.GREEN, "Nothing to load or unload: the plugins folder matches what's loaded.");
            printConflicts(sender, label, plan);
            for (Syncer.Line line : plan.notes) {
                send(sender, line);
            }
            return;
        }
        for (Syncer.Line line : syncer.apply(plan)) {
            send(sender, line);
        }
        printConflicts(sender, label, plan);
    }

    /** One line per plugin that has several jars, clickable for players. */
    private void printConflicts(CommandSender sender, String label, Syncer.Plan plan) {
        for (Map.Entry<String, List<JarInfo>> e : plan.conflicts.entrySet()) {
            List<String> described = new ArrayList<String>();
            for (JarInfo j : e.getValue()) {
                described.add(j.file.getName() + " (" + j.version + ")");
            }
            send(sender, new Syncer.Line(ChatColor.YELLOW,
                    "Multiple jars for " + e.getKey() + ": " + join(described)
                            + ". Choose one: /" + label + " choose " + e.getKey(),
                    "/" + label + " choose " + e.getKey()));
        }
    }

    // ----------------------------------------------------------------- choose

    /** /pluginsync choose [plugin] [number|gui] [-f] */
    private void choose(CommandSender sender, String label, String[] args) {
        boolean force = false;
        List<String> tokens = new ArrayList<String>();
        for (int i = 1; i < args.length; i++) {
            if (args[i].equalsIgnoreCase("-f") || args[i].equalsIgnoreCase("--force")) {
                force = true;
            } else {
                tokens.add(args[i]);
            }
        }

        reloadConfig();
        Syncer.Plan plan = syncer.scan();

        if (tokens.isEmpty()) {
            if (plan.conflicts.isEmpty()) {
                send(sender, ChatColor.GREEN, "No plugin has more than one jar in the plugins folder right now.");
            }
            for (Map.Entry<String, List<JarInfo>> e : plan.conflicts.entrySet()) {
                showOptions(sender, label, e.getKey(), e.getValue());
            }
            return;
        }

        List<JarInfo> candidates = plan.conflictsFor(tokens.get(0));
        if (candidates == null) {
            send(sender, ChatColor.RED, "There's only one jar (or none) for " + tokens.get(0)
                    + ". To re-read a replaced jar use /" + label + " reload " + tokens.get(0) + ".");
            return;
        }
        String name = candidates.get(0).name;

        if (tokens.size() == 1) {
            showOptions(sender, label, name, candidates);
            return;
        }

        String pick = tokens.get(1);
        if (pick.equalsIgnoreCase("gui")) {
            if (!(sender instanceof Player)) {
                send(sender, ChatColor.RED, "The menu is only for players. Use a number instead: /"
                        + label + " choose " + name + " <number>");
                return;
            }
            Plugin running = Bukkit.getPluginManager().getPlugin(name);
            JarInfo runningJar = running == null ? null : syncer.runningJar(running, candidates);
            menu.open((Player) sender, name, candidates, runningJar);
            return;
        }

        int index;
        try {
            index = Integer.parseInt(pick);
        } catch (NumberFormatException e) {
            send(sender, ChatColor.YELLOW, "Usage: /" + label + " choose " + name + " <number|gui> [-f]");
            return;
        }
        if (index < 1 || index > candidates.size()) {
            send(sender, ChatColor.RED, "Pick a number between 1 and " + candidates.size() + ".");
            return;
        }
        applyChoice(sender, name, candidates.get(index - 1), force);
    }

    private void showOptions(CommandSender sender, String label, String name, List<JarInfo> candidates) {
        Plugin running = Bukkit.getPluginManager().getPlugin(name);
        JarInfo runningJar = running == null ? null : syncer.runningJar(running, candidates);
        String state = running == null ? "not loaded"
                : "running " + String.valueOf(Syncer.versionOf(running));
        send(sender, ChatColor.AQUA, "Multiple jars for " + name + " (" + state + "):");
        for (int i = 0; i < candidates.size(); i++) {
            JarInfo j = candidates.get(i);
            boolean isRunning = j == runningJar;
            send(sender, new Syncer.Line(isRunning ? ChatColor.YELLOW : ChatColor.GREEN,
                    "  [" + (i + 1) + "] " + j.file.getName() + "  v" + j.version + (isRunning ? "  (running)" : ""),
                    "/" + label + " choose " + name + " " + (i + 1)));
        }
        if (sender instanceof Player) {
            send(sender, new Syncer.Line(ChatColor.AQUA, "  [Open menu]", "/" + label + " choose " + name + " gui"));
        }
        send(sender, ChatColor.GRAY, "Click a line, or run /" + label + " choose " + name
                + " <number>. Add -f if other plugins depend on it.");
    }

    /** Re-checks the folder (it may have changed since the list was shown), then applies the pick. */
    void applyChoice(CommandSender sender, String name, JarInfo requested, boolean force) {
        Syncer.Plan plan = syncer.scan();
        List<JarInfo> candidates = plan.conflictsFor(name);
        if (candidates == null) {
            send(sender, ChatColor.RED, "There's no longer more than one jar for " + name
                    + ". Use /pluginsync reload " + name + " to re-read its jar.");
            return;
        }
        JarInfo chosen = null;
        for (JarInfo j : candidates) {
            if (j.file.getAbsoluteFile().equals(requested.file.getAbsoluteFile())) {
                chosen = j;
                break;
            }
        }
        if (chosen == null) {
            send(sender, ChatColor.RED, requested.file.getName() + " is no longer in the plugins folder.");
            return;
        }
        for (Syncer.Line line : syncer.choose(chosen, candidates, force)) {
            send(sender, line);
        }
    }

    // --------------------------------------------- enable/disable/restart/reload

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
        if (target == this && !action.equals("reload")) {
            send(sender, ChatColor.RED, "PluginSync can't " + action + " itself. "
                    + "(/" + label + " reload PluginSync or /" + label + " update switch it to its newest jar.)");
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
            send(sender, line);
        }
    }

    private void usage(CommandSender sender, String label) {
        send(sender, ChatColor.YELLOW, "/" + label + "                     load new jars, unload removed ones");
        send(sender, ChatColor.YELLOW, "/" + label + " scan               preview what a sync would do");
        send(sender, ChatColor.YELLOW, "/" + label + " choose [plugin]    pick between several jars of one plugin");
        send(sender, ChatColor.YELLOW, "/" + label + " enable <plugin>    turn a disabled plugin on");
        send(sender, ChatColor.YELLOW, "/" + label + " disable <plugin>   turn a plugin off");
        send(sender, ChatColor.YELLOW, "/" + label + " restart <plugin>   disable, then enable");
        send(sender, ChatColor.YELLOW, "/" + label + " reload <plugin>    unload and load again from its jar");
        send(sender, ChatColor.YELLOW, "/" + label + " update             switch PluginSync itself to its newest jar");
        send(sender, ChatColor.GRAY, "Add -f to act even if other plugins depend on the target.");
    }

    // ---------------------------------------------------------- tab completion

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<String>();
        if (!sender.hasPermission("pluginsync.use")) {
            return out;
        }
        String first = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        if (args.length == 1) {
            addMatches(out, SUBCOMMANDS, args[0]);
        } else if (args.length == 2 && PLUGIN_ACTIONS.contains(first)) {
            List<String> names = new ArrayList<String>();
            for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
                if (p != this || first.equals("reload")) {
                    names.add(p.getName());
                }
            }
            addMatches(out, names, args[1]);
        } else if (args.length == 3 && PLUGIN_ACTIONS.contains(first)) {
            addMatches(out, Arrays.asList("-f"), args[2]);
        } else if (first.equals("choose")) {
            Syncer.Plan plan = syncer.scan();
            if (args.length == 2) {
                addMatches(out, new ArrayList<String>(plan.conflicts.keySet()), args[1]);
            } else if (args.length == 3) {
                List<JarInfo> candidates = plan.conflictsFor(args[1]);
                List<String> options = new ArrayList<String>();
                options.add("gui");
                if (candidates != null) {
                    for (int i = 1; i <= candidates.size(); i++) {
                        options.add(String.valueOf(i));
                    }
                }
                addMatches(out, options, args[2]);
            } else if (args.length == 4) {
                addMatches(out, Arrays.asList("-f"), args[3]);
            }
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

    private void preloadOwnClasses() {
        try {
            java.security.CodeSource source = getClass().getProtectionDomain().getCodeSource();
            if (source == null) {
                return;
            }
            File file = new File(source.getLocation().toURI());
            if (!file.isFile()) {
                return;
            }
            ClassLoader loader = getClass().getClassLoader();
            JarFile jar = new JarFile(file);
            try {
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    String entry = entries.nextElement().getName();
                    if (entry.endsWith(".class")) {
                        try {
                            Class.forName(entry.substring(0, entry.length() - 6).replace('/', '.'), false, loader);
                        } catch (Throwable ignored) {
                            // best effort
                        }
                    }
                }
            } finally {
                jar.close();
            }
        } catch (Throwable ignored) {
            // best effort: self-update then relies on lazy class loading
        }
    }

    // ---------------------------------------------------------------- messages

    private void send(CommandSender sender, ChatColor color, String text) {
        send(sender, new Syncer.Line(color, text));
    }

    private void send(CommandSender sender, Syncer.Line line) {
        String legacy = line.color + line.text;
        boolean sent = false;
        if (line.command != null && sender instanceof Player) {
            sent = Chat.sendClickable((Player) sender, legacy, line.command);
        }
        if (!sent) {
            sender.sendMessage(legacy);
        }
        if (!(sender instanceof ConsoleCommandSender)) {
            getLogger().info("[" + sender.getName() + "] " + line.text);
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
