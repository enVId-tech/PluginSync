package dev.etran;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

import java.io.Closeable;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
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
 */
final class Syncer {

    record Line(NamedTextColor color, String text) {}

    record Plan(List<JarInfo> toLoad, List<Plugin> toUnload, List<Line> notes) {
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
        File dir = self.getDataFolder().getParentFile();
        Path dirPath = dir.toPath().toAbsolutePath().normalize();
        List<Line> notes = new ArrayList<>();

        Set<String> ignore = new HashSet<>();
        for (String s : self.getConfig().getStringList("ignore")) {
            ignore.add(lower(s));
        }

        // Every plugin jar currently in the folder, keyed by the plugin name inside it.
        Map<String, JarInfo> byName = new LinkedHashMap<>();
        File[] jars = dir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".jar"));
        if (jars == null) {
            jars = new File[0];
        }
        Arrays.sort(jars);
        for (File jar : jars) {
            JarInfo info = JarInfo.read(jar);
            if (info == null) {
                continue; // not a plugin (library jar, etc.)
            }
            JarInfo existing = byName.putIfAbsent(lower(info.name()), info);
            if (existing != null) {
                notes.add(new Line(NamedTextColor.YELLOW, "Ignoring " + jar.getName()
                        + ": same plugin name as " + existing.file().getName()));
            }
        }

        // New: a jar whose plugin isn't loaded.
        List<JarInfo> toLoad = new ArrayList<>();
        for (JarInfo info : byName.values()) {
            if (ignore.contains(lower(info.name()))) {
                continue;
            }
            Plugin loaded = pm.getPlugin(info.name());
            if (loaded == null) {
                if (info.paperOnly()) {
                    notes.add(new Line(NamedTextColor.YELLOW, info.file().getName()
                            + " only has a paper-plugin.yml and can't be loaded at runtime; restart needed"));
                } else {
                    toLoad.add(info);
                }
            } else {
                String running = loaded.getPluginMeta().getVersion();
                if (!running.equals(info.version())) {
                    notes.add(new Line(NamedTextColor.YELLOW, info.name() + ": jar is " + info.version()
                            + " but " + running + " is running (restart to update)"));
                }
            }
        }

        // Removed: a loaded plugin (from the plugins folder) with no matching jar anymore.
        List<Plugin> toUnload = new ArrayList<>();
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
        List<Line> out = new ArrayList<>();
        boolean changed = false;

        // 1. Unload removed plugins, dependents first.
        List<Plugin> pending = new ArrayList<>(plan.toUnload());
        boolean progress = true;
        while (!pending.isEmpty() && progress) {
            progress = false;
            for (Iterator<Plugin> it = pending.iterator(); it.hasNext(); ) {
                Plugin p = it.next();
                boolean waitingOnOther = pending.stream().anyMatch(o -> o != p && dependsOn(o, p, true));
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
                    out.add(new Line(NamedTextColor.RED, "Not unloading " + p.getName() + ": "
                            + blocker.getName() + " depends on it"));
                    continue;
                }
                try {
                    unload(p);
                    changed = true;
                    out.add(new Line(NamedTextColor.GOLD, "Unloaded " + p.getName()));
                } catch (Throwable t) {
                    self.getLogger().log(Level.WARNING, "Failed to unload " + p.getName(), t);
                    out.add(new Line(NamedTextColor.RED, "Failed to unload " + p.getName() + ": " + describe(t)));
                }
            }
        }
        for (Plugin p : pending) {
            out.add(new Line(NamedTextColor.RED, "Not unloading " + p.getName() + ": circular dependency"));
        }

        // 2. Load new plugins, dependencies first.
        for (JarInfo info : order(plan.toLoad())) {
            Plugin p = null;
            try {
                p = pm.loadPlugin(info.file());
                if (p == null) {
                    out.add(new Line(NamedTextColor.RED, "Failed to load " + info.file().getName()));
                    continue;
                }
                p.onLoad();
                pm.enablePlugin(p);
                if (p.isEnabled()) {
                    changed = true;
                    out.add(new Line(NamedTextColor.GREEN, "Loaded " + info.name() + " " + info.version()));
                } else {
                    changed = true;
                    cleanup(p);
                    out.add(new Line(NamedTextColor.RED, info.name()
                            + " loaded but failed to enable (see console); it was unloaded again"));
                }
            } catch (Throwable t) {
                self.getLogger().log(Level.WARNING, "Failed to load " + info.file().getName(), t);
                if (p != null) {
                    cleanup(p);
                }
                out.add(new Line(NamedTextColor.RED, "Failed to load " + info.file().getName() + ": " + describe(t)));
            }
        }

        if (changed) {
            refreshCommands();
        }
        out.addAll(plan.notes());
        return out;
    }

    // ---------------------------------------------------------------- unload

    private void unload(Plugin plugin) throws Exception {
        PluginManager pm = Bukkit.getPluginManager();
        ClassLoader loader = plugin.getClass().getClassLoader();

        pm.disablePlugin(plugin);   // cancels tasks, listeners, services, channels
        removeCommands(plugin);
        removeFromManager(pm, plugin);

        if (loader instanceof Closeable closeable) {
            closeable.close();      // releases the jar file (matters on Windows)
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
        CommandMap map = Bukkit.getCommandMap();
        Map<String, Command> known = map.getKnownCommands();
        List<String> keys = new ArrayList<>();
        for (Map.Entry<String, Command> e : known.entrySet()) {
            if (e.getValue() instanceof PluginCommand pc && pc.getPlugin() == plugin) {
                keys.add(e.getKey());
            }
        }
        for (String key : keys) {
            Command removed = known.remove(key);
            if (removed != null) {
                removed.unregister(map);
            }
        }
    }

    /**
     * Bukkit has no API for forgetting a plugin, so remove it from the manager's internal
     * plugin list / name lookup by reflection. Paper keeps these in an inner "instanceManager".
     */
    private void removeFromManager(PluginManager pm, Plugin plugin) {
        List<Object> targets = new ArrayList<>();
        targets.add(pm);
        Object inner = readField(pm, "instanceManager");
        if (inner != null) {
            targets.add(inner);
        }
        for (Object target : targets) {
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
                        if (value instanceof List<?> list) {
                            list.remove(plugin);
                        } else if (value instanceof Map<?, ?> map) {
                            map.values().removeIf(v -> v == plugin);
                        }
                    } catch (Exception ignored) {
                        // immutable or inaccessible; not the field we need
                    }
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
            } catch (Exception ignored) {
                // try superclass
            }
        }
        return null;
    }

    // ----------------------------------------------------------------- misc

    /** Tell the server and connected players about added / removed commands. */
    private void refreshCommands() {
        try {
            Object server = Bukkit.getServer();
            server.getClass().getMethod("syncCommands").invoke(server);
        } catch (Throwable ignored) {
            // not available on this server build; tab-complete just won't refresh
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.updateCommands();
        }
    }

    @SuppressWarnings("deprecation")
    private static boolean dependsOn(Plugin q, Plugin p, boolean includeSoft) {
        try {
            var desc = q.getDescription();
            if (containsIgnoreCase(desc.getDepend(), p.getName())) {
                return true;
            }
            return includeSoft && containsIgnoreCase(desc.getSoftDepend(), p.getName());
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean containsIgnoreCase(List<String> list, String name) {
        for (String s : list) {
            if (s.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /** Order jars so that anything a jar depends on (among the new jars) loads first. */
    private static List<JarInfo> order(List<JarInfo> jars) {
        Map<String, JarInfo> byName = new LinkedHashMap<>();
        for (JarInfo j : jars) {
            byName.put(lower(j.name()), j);
        }
        List<JarInfo> out = new ArrayList<>();
        Set<String> done = new HashSet<>();
        Set<String> visiting = new HashSet<>();
        for (JarInfo j : jars) {
            visit(j, byName, done, visiting, out);
        }
        return out;
    }

    private static void visit(JarInfo j, Map<String, JarInfo> byName, Set<String> done,
                              Set<String> visiting, List<JarInfo> out) {
        String key = lower(j.name());
        if (done.contains(key) || !visiting.add(key)) {
            return;
        }
        for (String dep : j.deps()) {
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
            var source = p.getClass().getProtectionDomain().getCodeSource();
            if (source == null) {
                return null;
            }
            return Path.of(source.getLocation().toURI()).toAbsolutePath().normalize();
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
