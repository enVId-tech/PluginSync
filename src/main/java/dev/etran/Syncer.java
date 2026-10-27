package dev.etran;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

import java.io.Closeable;
import java.io.File;
import java.io.FilenameFilter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

/**
 * Works out which plugin jars were added to / removed from the plugins folder since the server
 * started, and loads / unloads only those. Plugins that are already running are never touched.
 *
 * Compiled against the Bukkit 1.8 API so one jar runs on every newer server. Anything that does
 * not exist in 1.8 (or changed since) is reached through reflection and is optional.
 */
final class Syncer {

    static final class Line {
        final ChatColor color;
        final String text;

        Line(ChatColor color, String text) {
            this.color = color;
            this.text = text;
        }
    }

    static final class Plan {
        final List<JarInfo> toLoad;
        final List<Plugin> toUnload;
        final List<Line> notes;

        Plan(List<JarInfo> toLoad, List<Plugin> toUnload, List<Line> notes) {
            this.toLoad = toLoad;
            this.toUnload = toUnload;
            this.notes = notes;
        }

        boolean hasChanges() {
            return !toLoad.isEmpty() || !toUnload.isEmpty();
        }
    }

    private final PluginSyncPlugin self;

    Syncer(PluginSyncPlugin self) {
        this.self = self;
    }

    // ------------------------------------------------------------------ scan

    Plan scan() {
        PluginManager pm = Bukkit.getPluginManager();
        File dir = self.getDataFolder().getAbsoluteFile().getParentFile();
        Path dirPath = dir.toPath().toAbsolutePath().normalize();
        List<Line> notes = new ArrayList<Line>();

        Set<String> ignore = new HashSet<String>();
        for (String s : self.getConfig().getStringList("ignore")) {
            ignore.add(lower(s));
        }

        // Every plugin jar currently in the folder, keyed by the plugin name inside it.
        Map<String, JarInfo> byName = new LinkedHashMap<String, JarInfo>();
        File[] jars = dir.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File d, String n) {
                return n.toLowerCase(Locale.ROOT).endsWith(".jar");
            }
        });
        if (jars == null) {
            jars = new File[0];
        }
        Arrays.sort(jars);
        for (File jar : jars) {
            JarInfo info = JarInfo.read(jar);
            if (info == null) {
                continue; // not a plugin (library jar, etc.)
            }
            JarInfo existing = byName.get(lower(info.name));
            if (existing != null) {
                notes.add(new Line(ChatColor.YELLOW, "Ignoring " + jar.getName()
                        + ": same plugin name as " + existing.file.getName()));
            } else {
                byName.put(lower(info.name), info);
            }
        }

        // New: a jar whose plugin isn't loaded.
        List<JarInfo> toLoad = new ArrayList<JarInfo>();
        for (JarInfo info : byName.values()) {
            if (ignore.contains(lower(info.name))) {
                continue;
            }
            Plugin loaded = pm.getPlugin(info.name);
            if (loaded == null) {
                if (info.paperOnly) {
                    notes.add(new Line(ChatColor.YELLOW, info.file.getName()
                            + " only has a paper-plugin.yml and can't be loaded at runtime; restart needed"));
                } else {
                    toLoad.add(info);
                }
            } else {
                String running = versionOf(loaded);
                if (running != null && !running.equals(info.version)) {
                    notes.add(new Line(ChatColor.YELLOW, info.name + ": jar is " + info.version
                            + " but " + running + " is running (restart to update)"));
                }
            }
        }

        // Removed: a loaded plugin (from the plugins folder) with no matching jar anymore.
        List<Plugin> toUnload = new ArrayList<Plugin>();
        for (Plugin p : pm.getPlugins()) {
            if (p == self || ignore.contains(lower(p.getName())) || byName.containsKey(lower(p.getName()))) {
                continue;
            }
            Path location = locationOf(p);
            if (location == null || !location.startsWith(dirPath)) {
                continue; // loaded from somewhere else, not our business
            }
            toUnload.add(p);
        }

        return new Plan(toLoad, toUnload, notes);
    }

    // ----------------------------------------------------------------- apply

    List<Line> apply(Plan plan) {
        PluginManager pm = Bukkit.getPluginManager();
        List<Line> out = new ArrayList<Line>();
        boolean changed = false;

        // 1. Unload removed plugins, dependents first.
        List<Plugin> pending = new ArrayList<Plugin>(plan.toUnload);
        boolean progress = true;
        while (!pending.isEmpty() && progress) {
            progress = false;
            for (Iterator<Plugin> it = pending.iterator(); it.hasNext(); ) {
                Plugin p = it.next();
                boolean waitingOnOther = false;
                for (Plugin o : pending) {
                    if (o != p && dependsOn(o, p, true)) {
                        waitingOnOther = true;
                        break;
                    }
                }
                if (waitingOnOther) {
                    continue;
                }
                it.remove();
                progress = true;

                Plugin blocker = null;
                for (Plugin q : pm.getPlugins()) {
                    if (q != p && dependsOn(q, p, false)) {
                        blocker = q;
                        break;
                    }
                }
                if (blocker != null) {
                    out.add(new Line(ChatColor.RED, "Not unloading " + p.getName() + ": "
                            + blocker.getName() + " depends on it"));
                    continue;
                }
                try {
                    unload(p);
                    changed = true;
                    out.add(new Line(ChatColor.GOLD, "Unloaded " + p.getName()));
                } catch (Throwable t) {
                    self.getLogger().log(Level.WARNING, "Failed to unload " + p.getName(), t);
                    out.add(new Line(ChatColor.RED, "Failed to unload " + p.getName() + ": " + describe(t)));
                }
            }
        }
        for (Plugin p : pending) {
            out.add(new Line(ChatColor.RED, "Not unloading " + p.getName() + ": circular dependency"));
        }

        // 2. Load new plugins, dependencies first.
        for (JarInfo info : order(plan.toLoad)) {
            if (loadJar(info, out)) {
                changed = true;
            }
        }

        if (changed) {
            refreshCommands();
        }
        out.addAll(plan.notes);
        return out;
    }

    /** Loads and enables one jar. Returns true if the set of loaded plugins may have changed. */
    private boolean loadJar(JarInfo info, List<Line> out) {
        PluginManager pm = Bukkit.getPluginManager();
        Plugin p = null;
        try {
            p = pm.loadPlugin(info.file);
            if (p == null) {
                out.add(new Line(ChatColor.RED, "Failed to load " + info.file.getName()));
                return false;
            }
            p.onLoad();
            pm.enablePlugin(p);
            if (p.isEnabled()) {
                out.add(new Line(ChatColor.GREEN, "Loaded " + info.name + " " + info.version));
            } else {
                cleanup(p);
                out.add(new Line(ChatColor.RED, info.name
                        + " loaded but failed to enable (see console); it was unloaded again"));
            }
            return true;
        } catch (Throwable t) {
            self.getLogger().log(Level.WARNING, "Failed to load " + info.file.getName(), t);
            if (p != null) {
                cleanup(p);
            }
            out.add(new Line(ChatColor.RED, "Failed to load " + info.file.getName() + ": " + describe(t)));
            return p != null;
        }
    }

    // ------------------------------------------------- single-plugin actions

    /** Turn a loaded-but-disabled plugin on. */
    List<Line> enable(Plugin p) {
        List<Line> out = new ArrayList<Line>();
        if (p.isEnabled()) {
            out.add(new Line(ChatColor.YELLOW, p.getName() + " is already enabled"));
            return out;
        }
        Bukkit.getPluginManager().enablePlugin(p);
        if (p.isEnabled()) {
            refreshCommands();
            out.add(new Line(ChatColor.GREEN, "Enabled " + p.getName()));
        } else {
            out.add(new Line(ChatColor.RED, p.getName() + " failed to enable (see console)"));
        }
        return out;
    }

    /** Turn an enabled plugin off (it stays loaded, so /pluginsync enable brings it back). */
    List<Line> disable(Plugin p, boolean force) {
        List<Line> out = new ArrayList<Line>();
        if (!p.isEnabled()) {
            out.add(new Line(ChatColor.YELLOW, p.getName() + " is already disabled"));
            return out;
        }
        List<String> dependents = enabledDependents(p);
        if (!dependents.isEmpty() && !force) {
            out.add(blockedBy(p, dependents));
            return out;
        }
        Bukkit.getPluginManager().disablePlugin(p);
        removeCommands(p);
        refreshCommands();
        out.add(new Line(ChatColor.GOLD, "Disabled " + p.getName()));
        if (!dependents.isEmpty()) {
            out.add(forcedWarning(dependents));
        }
        return out;
    }

    /** Disable then enable the same plugin instance (no jar re-read, no new classes). */
    List<Line> restart(Plugin p, boolean force) {
        List<Line> out = new ArrayList<Line>();
        PluginManager pm = Bukkit.getPluginManager();
        List<String> dependents = enabledDependents(p);
        if (p.isEnabled() && !dependents.isEmpty() && !force) {
            out.add(blockedBy(p, dependents));
            return out;
        }
        if (p.isEnabled()) {
            pm.disablePlugin(p);
            removeCommands(p);
        }
        pm.enablePlugin(p);
        refreshCommands();
        if (p.isEnabled()) {
            out.add(new Line(ChatColor.GREEN, "Restarted " + p.getName()));
        } else {
            out.add(new Line(ChatColor.RED, p.getName() + " failed to enable again (see console)"));
        }
        if (!dependents.isEmpty()) {
            out.add(forcedWarning(dependents));
        }
        return out;
    }

    /** Unload the plugin and load it again from its jar, so a replaced jar takes effect. */
    List<Line> reload(Plugin p, boolean force) {
        List<Line> out = new ArrayList<Line>();
        List<String> dependents = enabledDependents(p);
        if (!dependents.isEmpty() && !force) {
            out.add(blockedBy(p, dependents));
            return out;
        }
        JarInfo jar = findJar(p.getName());
        if (jar == null) {
            out.add(new Line(ChatColor.RED, "Can't reload " + p.getName()
                    + ": no jar with that plugin name in the plugins folder"));
            return out;
        }
        if (jar.paperOnly) {
            out.add(new Line(ChatColor.RED, "Can't reload " + p.getName()
                    + ": its jar only has a paper-plugin.yml, which can't be loaded at runtime"));
            return out;
        }
        try {
            unload(p);
            out.add(new Line(ChatColor.GOLD, "Unloaded " + p.getName()));
        } catch (Throwable t) {
            self.getLogger().log(Level.WARNING, "Failed to unload " + p.getName(), t);
            out.add(new Line(ChatColor.RED, "Failed to unload " + p.getName() + ": " + describe(t)));
            return out;
        }
        loadJar(jar, out);
        refreshCommands();
        if (!dependents.isEmpty()) {
            out.add(forcedWarning(dependents));
        }
        return out;
    }

    private List<String> enabledDependents(Plugin p) {
        List<String> names = new ArrayList<String>();
        for (Plugin q : Bukkit.getPluginManager().getPlugins()) {
            if (q != p && q.isEnabled() && dependsOn(q, p, false)) {
                names.add(q.getName());
            }
        }
        return names;
    }

    private static Line blockedBy(Plugin p, List<String> dependents) {
        return new Line(ChatColor.RED, "Not touching " + p.getName() + ": " + join(dependents)
                + " depend(s) on it. Add -f to do it anyway.");
    }

    private static Line forcedWarning(List<String> dependents) {
        return new Line(ChatColor.YELLOW, "Warning: " + join(dependents)
                + " depend(s) on this plugin and may misbehave until restarted.");
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

    /** The jar in the plugins folder whose plugin.yml name matches, or null. */
    private JarInfo findJar(String pluginName) {
        File dir = self.getDataFolder().getAbsoluteFile().getParentFile();
        File[] jars = dir.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File d, String n) {
                return n.toLowerCase(Locale.ROOT).endsWith(".jar");
            }
        });
        if (jars == null) {
            return null;
        }
        Arrays.sort(jars);
        for (File jar : jars) {
            JarInfo info = JarInfo.read(jar);
            if (info != null && info.name.equalsIgnoreCase(pluginName)) {
                return info;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- unload

    private void unload(Plugin plugin) throws Exception {
        PluginManager pm = Bukkit.getPluginManager();
        ClassLoader loader = plugin.getClass().getClassLoader();
        Object pluginLoader = null;
        try {
            pluginLoader = plugin.getPluginLoader();
        } catch (Throwable ignored) {
            // some server builds don't expose it
        }

        pm.disablePlugin(plugin);   // cancels tasks, listeners, services, channels
        removeCommands(plugin);

        // Bukkit has no API for forgetting a plugin, so remove it from the manager's internal
        // plugin list / name lookup by reflection. Paper keeps these in an inner "instanceManager".
        purge(pm, plugin);
        purge(readField(pm, "instanceManager"), plugin);
        if (pluginLoader != null) {
            purge(pluginLoader, loader);   // the loader's registry of plugin class loaders
        }

        if (loader instanceof Closeable) {
            ((Closeable) loader).close();  // releases the jar file (matters on Windows)
        }
    }

    private void cleanup(Plugin plugin) {
        try {
            unload(plugin);
        } catch (Throwable t) {
            self.getLogger().log(Level.WARNING, "Cleanup of " + plugin.getName() + " failed", t);
        }
    }

    private void removeCommands(Plugin plugin) {
        Object commandMap = commandMap();
        if (commandMap == null) {
            return;
        }
        Map<String, Command> known = knownCommands(commandMap);
        if (known == null) {
            return;
        }
        List<String> keys = new ArrayList<String>();
        for (Map.Entry<String, Command> e : known.entrySet()) {
            Command c = e.getValue();
            if (c instanceof PluginCommand && ((PluginCommand) c).getPlugin() == plugin) {
                keys.add(e.getKey());
            }
        }
        for (String key : keys) {
            Command removed = known.remove(key);
            if (removed != null && commandMap instanceof CommandMap) {
                removed.unregister((CommandMap) commandMap);
            }
        }
    }

    /** CraftServer#getCommandMap exists on every CraftBukkit/Spigot/Paper version. */
    private static Object commandMap() {
        try {
            Object server = Bukkit.getServer();
            return server.getClass().getMethod("getCommandMap").invoke(server);
        } catch (Throwable t) {
            return null;
        }
    }

    /** CommandMap#getKnownCommands exists on 1.13+; older servers keep a protected field. */
    @SuppressWarnings("unchecked")
    private static Map<String, Command> knownCommands(Object commandMap) {
        try {
            Method m = commandMap.getClass().getMethod("getKnownCommands");
            return (Map<String, Command>) m.invoke(commandMap);
        } catch (Throwable ignored) {
            // fall through to field access
        }
        for (Class<?> c = commandMap.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField("knownCommands");
                f.setAccessible(true);
                return (Map<String, Command>) f.get(commandMap);
            } catch (Throwable ignored) {
                // try superclass
            }
        }
        return null;
    }

    /** Removes {@code victim} from every list / map field of {@code target}. */
    private static void purge(Object target, final Object victim) {
        if (target == null || victim == null) {
            return;
        }
        for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                if (!List.class.isAssignableFrom(f.getType()) && !Map.class.isAssignableFrom(f.getType())) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    Object value = f.get(target);
                    if (value instanceof List) {
                        ((List<?>) value).remove(victim);
                    } else if (value instanceof Map) {
                        Iterator<?> it = ((Map<?, ?>) value).values().iterator();
                        while (it.hasNext()) {
                            if (it.next() == victim) {
                                it.remove();
                            }
                        }
                    }
                } catch (Throwable ignored) {
                    // immutable or inaccessible; not the field we need
                }
            }
        }
    }

    private static Object readField(Object target, String name) {
        for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (Throwable ignored) {
                // try superclass
            }
        }
        return null;
    }

    // ----------------------------------------------------------------- misc

    /** Tell the server and connected players about added / removed commands (1.13+ only). */
    private void refreshCommands() {
        try {
            Object server = Bukkit.getServer();
            server.getClass().getMethod("syncCommands").invoke(server);
        } catch (Throwable ignored) {
            // not available on this server build
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            try {
                player.getClass().getMethod("updateCommands").invoke(player);
            } catch (Throwable ignored) {
                // pre-1.13: clients get no command tree to refresh
            }
        }
    }

    @SuppressWarnings("deprecation")
    private static boolean dependsOn(Plugin q, Plugin p, boolean includeSoft) {
        try {
            if (containsIgnoreCase(q.getDescription().getDepend(), p.getName())) {
                return true;
            }
            return includeSoft && containsIgnoreCase(q.getDescription().getSoftDepend(), p.getName());
        } catch (Throwable t) {
            return false;
        }
    }

    /** Running version: getDescription() on most servers, getPluginMeta() if that is all there is. */
    @SuppressWarnings("deprecation")
    private static String versionOf(Plugin p) {
        try {
            return p.getDescription().getVersion();
        } catch (Throwable ignored) {
            // fall through
        }
        try {
            Object meta = p.getClass().getMethod("getPluginMeta").invoke(p);
            Class<?> metaType = Class.forName("io.papermc.paper.plugin.configuration.PluginMeta");
            return String.valueOf(metaType.getMethod("getVersion").invoke(meta));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean containsIgnoreCase(List<String> list, String name) {
        if (list == null) {
            return false;
        }
        for (String s : list) {
            if (s.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /** Order jars so that anything a jar depends on (among the new jars) loads first. */
    private static List<JarInfo> order(List<JarInfo> jars) {
        Map<String, JarInfo> byName = new LinkedHashMap<String, JarInfo>();
        for (JarInfo j : jars) {
            byName.put(lower(j.name), j);
        }
        List<JarInfo> out = new ArrayList<JarInfo>();
        Set<String> done = new HashSet<String>();
        Set<String> visiting = new HashSet<String>();
        for (JarInfo j : jars) {
            visit(j, byName, done, visiting, out);
        }
        return out;
    }

    private static void visit(JarInfo j, Map<String, JarInfo> byName, Set<String> done,
                              Set<String> visiting, List<JarInfo> out) {
        String key = lower(j.name);
        if (done.contains(key) || !visiting.add(key)) {
            return;
        }
        for (String dep : j.deps) {
            JarInfo d = byName.get(lower(dep));
            if (d != null) {
                visit(d, byName, done, visiting, out);
            }
        }
        visiting.remove(key);
        done.add(key);
        out.add(j);
    }

    private static Path locationOf(Plugin p) {
        try {
            java.security.CodeSource source = p.getClass().getProtectionDomain().getCodeSource();
            if (source == null) {
                return null;
            }
            return Paths.get(source.getLocation().toURI()).toAbsolutePath().normalize();
        } catch (Exception e) {
            return null;
        }
    }

    private static String describe(Throwable t) {
        String msg = t.getMessage();
        return t.getClass().getSimpleName() + (msg == null ? "" : ": " + msg);
    }

    private static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
