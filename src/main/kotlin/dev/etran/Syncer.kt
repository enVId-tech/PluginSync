package dev.etran

import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.command.Command
import org.bukkit.command.CommandMap
import org.bukkit.command.PluginCommand
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.plugin.Plugin
import java.io.Closeable
import java.io.File
import java.io.FilenameFilter
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale
import java.util.logging.Level

/**
 * Works out which plugin jars were added to / removed from the plugins folder since the server
 * started, and loads / unloads only those. Plugins that are already running are never touched.
 *
 * Compiled against the Bukkit 1.8 API so one jar runs on every newer server. Anything that does
 * not exist in 1.8 (or changed since) is reached through reflection and is optional.
 */
class Syncer(private val self: PluginSyncPlugin) {

    class Line(
        val color: ChatColor,
        val text: String,
        /** Optional command (with leading slash) that a player can click to run. */
        val command: String? = null
    )

    class Plan(
        val toLoad: List<JarInfo>,
        val toUnload: List<Plugin>,
        val notes: List<Line>,
        /** Plugins that have more than one jar in the folder: plugin name -> candidate jars. */
        val conflicts: Map<String, List<JarInfo>>
    ) {
        fun hasChanges(): Boolean = toLoad.isNotEmpty() || toUnload.isNotEmpty()

        /** Candidate jars for a plugin name (case-insensitive), or null if it has no conflict. */
        fun conflictsFor(name: String): List<JarInfo>? {
            for ((key, value) in conflicts) {
                if (key.equals(name, ignoreCase = true)) {
                    return value
                }
            }
            return null
        }
    }

    // ------------------------------------------------------------------ scan

    fun scan(): Plan {
        val pm = Bukkit.getPluginManager()
        val dir = self.dataFolder.absoluteFile.parentFile
        val dirPath = dir.toPath().toAbsolutePath().normalize()
        val notes = ArrayList<Line>()

        val ignore = HashSet<String>()
        for (s in self.config.getStringList("ignore")) {
            ignore.add(lower(s))
        }

        // Every plugin jar currently in the folder, grouped by the plugin name inside it.
        val groups = LinkedHashMap<String, MutableList<JarInfo>>()
        for (info in listJars(dir)) {
            groups.getOrPut(lower(info.name)) { ArrayList() }.add(info)
        }

        // Plugins with several jars can't be handled automatically: the user has to pick one.
        // Those are reported as conflicts and left alone by sync (a running plugin keeps running).
        val conflicts = LinkedHashMap<String, List<JarInfo>>()
        val byName = LinkedHashMap<String, JarInfo>()
        for ((key, group) in groups) {
            val first = group[0]
            if (group.size > 1 && !ignore.contains(key)) {
                conflicts[first.name] = group
            }
            byName[key] = first // used below to know a loaded plugin still has a jar
        }

        // New: a jar whose plugin isn't loaded.
        val toLoad = ArrayList<JarInfo>()
        for ((_, group) in groups) {
            if (group.size > 1) {
                continue // conflict, see above
            }
            val info = group[0]
            if (ignore.contains(lower(info.name))) {
                continue
            }
            val loaded = pm.getPlugin(info.name)
            if (loaded == null) {
                if (info.paperOnly) {
                    notes.add(Line(ChatColor.YELLOW, info.file.name +
                        " only has a paper-plugin.yml and can't be loaded at runtime; restart needed"))
                } else {
                    toLoad.add(info)
                }
            } else {
                val running = versionOf(loaded)
                if (running != null && running != info.version) {
                    notes.add(Line(ChatColor.YELLOW, info.name + ": jar is " + info.version +
                        " but " + running + " is running. Switch with /pluginsync reload " + info.name,
                        "/pluginsync reload " + info.name))
                }
            }
        }

        // Removed: a loaded plugin (from the plugins folder) with no matching jar anymore.
        val toUnload = ArrayList<Plugin>()
        for (p in pm.plugins) {
            if (p === self || ignore.contains(lower(p.name)) || byName.containsKey(lower(p.name))) {
                continue
            }
            val location = locationOf(p)
            if (location == null || !location.startsWith(dirPath)) {
                continue // loaded from somewhere else, not our business
            }
            toUnload.add(p)
        }

        return Plan(toLoad, toUnload, notes, conflicts)
    }

    // ------------------------------------------------- choosing between jars

    /** Which candidate jar the running plugin was loaded from, or null if that can't be told. */
    fun runningJar(running: Plugin, candidates: List<JarInfo>): JarInfo? {
        val location = locationOf(running)
        if (location != null) {
            for (j in candidates) {
                if (j.file.toPath().toAbsolutePath().normalize() == location) {
                    return j
                }
            }
        }
        // Paper may run a remapped copy from a different path; fall back to matching the version.
        val version = versionOf(running)
        if (version != null) {
            var match: JarInfo? = null
            var count = 0
            for (j in candidates) {
                if (version == j.version) {
                    match = j
                    count++
                }
            }
            if (count == 1) {
                return match
            }
        }
        return null
    }

    /**
     * Make [chosen] the jar that is used for its plugin: swap it in if another version is
     * running, load it if nothing is running, and (optionally) move the other jars out of the way
     * so the server doesn't hit "ambiguous plugin name" on the next restart.
     */
    fun choose(chosen: JarInfo, candidates: List<JarInfo>, force: Boolean): List<Line> {
        val out = ArrayList<Line>()
        val pm = Bukkit.getPluginManager()

        if (chosen.paperOnly) {
            out.add(Line(ChatColor.RED, chosen.file.name +
                " only has a paper-plugin.yml and can't be loaded at runtime; restart needed"))
            return out
        }

        val running = pm.getPlugin(chosen.name)
        val ok: Boolean
        if (running == null) {
            ok = loadAndCheck(chosen, out)
        } else if (runningJar(running, candidates) === chosen) {
            out.add(Line(ChatColor.GREEN, "Keeping " + chosen.name + " " + chosen.version +
                " (" + chosen.file.name + ") as it is already running"))
            ok = true
        } else if (running === self) {
            ok = selfReload(chosen, out)
        } else {
            val dependents = enabledDependents(running)
            if (dependents.isNotEmpty() && !force) {
                out.add(blockedBy(running, dependents))
                return out
            }
            try {
                unload(running)
                out.add(Line(ChatColor.GOLD, "Unloaded " + running.name))
            } catch (t: Throwable) {
                self.logger.log(Level.WARNING, "Failed to unload " + running.name, t)
                out.add(Line(ChatColor.RED, "Failed to unload " + running.name + ": " + describe(t)))
                return out
            }
            ok = loadAndCheck(chosen, out)
            refreshCommands()
            if (dependents.isNotEmpty()) {
                out.add(forcedWarning(dependents))
            }
        }

        if (ok && self.config.getBoolean("archive-unchosen", true)) {
            archiveOthers(chosen, candidates, out)
        }
        return out
    }

    private fun loadAndCheck(jar: JarInfo, out: MutableList<Line>): Boolean {
        loadJar(jar, out)
        refreshCommands()
        val now = Bukkit.getPluginManager().getPlugin(jar.name)
        val ok = now != null && now.isEnabled
        if (!ok) {
            out.add(Line(ChatColor.YELLOW, jar.name + " is not loaded right now. Fix the problem shown " +
                "above (see the console), then run /pluginsync to load it again."))
        }
        return ok
    }

    /** Move the jars that were not chosen into plugins/PluginSync/archived/ (never deletes). */
    private fun archiveOthers(chosen: JarInfo, candidates: List<JarInfo>, out: MutableList<Line>) {
        val archive = File(self.dataFolder, "archived")
        for (other in candidates) {
            if (other === chosen) {
                continue
            }
            try {
                Files.createDirectories(archive.toPath())
                var target = File(archive, other.file.name).toPath()
                if (Files.exists(target)) {
                    target = File(archive, System.currentTimeMillis().toString() + "-" + other.file.name).toPath()
                }
                Files.move(other.file.toPath(), target)
                out.add(Line(ChatColor.GRAY, "Moved " + other.file.name +
                    " to plugins/PluginSync/archived/ (set archive-unchosen: false to keep unused jars in place)"))
            } catch (t: Throwable) {
                self.logger.log(Level.WARNING, "Could not move " + other.file.name, t)
                out.add(Line(ChatColor.YELLOW, "Couldn't move " + other.file.name + " (" + describe(t) +
                    "); remove it by hand or the server will complain about it on restart"))
            }
        }
    }

    // ----------------------------------------------------------------- apply

    fun applyPlan(plan: Plan): List<Line> {
        val pm = Bukkit.getPluginManager()
        val out = ArrayList<Line>()
        var changed = false

        // 1. Unload removed plugins, dependents first.
        val pending = ArrayList(plan.toUnload)
        var progress = true
        while (pending.isNotEmpty() && progress) {
            progress = false
            val it = pending.iterator()
            while (it.hasNext()) {
                val p = it.next()
                var waitingOnOther = false
                for (o in pending) {
                    if (o !== p && dependsOn(o, p, true)) {
                        waitingOnOther = true
                        break
                    }
                }
                if (waitingOnOther) {
                    continue
                }
                it.remove()
                progress = true

                var blocker: Plugin? = null
                for (q in pm.plugins) {
                    if (q !== p && dependsOn(q, p, false)) {
                        blocker = q
                        break
                    }
                }
                if (blocker != null) {
                    out.add(Line(ChatColor.RED, "Not unloading " + p.name + ": " + blocker.name + " depends on it"))
                    continue
                }
                try {
                    unload(p)
                    changed = true
                    out.add(Line(ChatColor.GOLD, "Unloaded " + p.name))
                } catch (t: Throwable) {
                    self.logger.log(Level.WARNING, "Failed to unload " + p.name, t)
                    out.add(Line(ChatColor.RED, "Failed to unload " + p.name + ": " + describe(t)))
                }
            }
        }
        for (p in pending) {
            out.add(Line(ChatColor.RED, "Not unloading " + p.name + ": circular dependency"))
        }

        // 2. Load new plugins, dependencies first.
        for (info in order(plan.toLoad)) {
            if (loadJar(info, out)) {
                changed = true
            }
        }

        if (changed) {
            refreshCommands()
        }
        out.addAll(plan.notes)
        return out
    }

    /** Loads and enables one jar. Returns true if the set of loaded plugins may have changed. */
    private fun loadJar(info: JarInfo, out: MutableList<Line>): Boolean {
        val pm = Bukkit.getPluginManager()
        var p: Plugin? = null
        try {
            p = pm.loadPlugin(info.file)
            if (p == null) {
                out.add(Line(ChatColor.RED, "Failed to load " + info.file.name))
                return false
            }
            p.onLoad()
            pm.enablePlugin(p)
            if (p.isEnabled) {
                out.add(Line(ChatColor.GREEN, "Loaded " + info.name + " " + info.version))
            } else {
                cleanup(p)
                out.add(Line(ChatColor.RED, info.name + " loaded but failed to enable (see console); it was unloaded again"))
            }
            return true
        } catch (t: Throwable) {
            self.logger.log(Level.WARNING, "Failed to load " + info.file.name, t)
            // Some servers register the plugin before the failure surfaces. Nothing with this
            // name was loaded when we started (callers guarantee that), so anything found now
            // is a half-registered leftover and must go, or the next attempt hits a ghost.
            val leftover = p ?: pm.getPlugin(info.name)
            if (leftover != null) {
                cleanup(leftover)
            }
            out.add(Line(ChatColor.RED, "Failed to load " + info.file.name + ": " + describe(t)))
            out.add(Line(ChatColor.GRAY, "The server console has the full error and usually a more specific " +
                "reason logged just above it."))
            return true
        }
    }

    // ------------------------------------------------- single-plugin actions

    /** Turn a loaded-but-disabled plugin on. */
    fun enable(p: Plugin): List<Line> {
        val out = ArrayList<Line>()
        if (p.isEnabled) {
            out.add(Line(ChatColor.YELLOW, p.name + " is already enabled"))
            return out
        }
        Bukkit.getPluginManager().enablePlugin(p)
        if (p.isEnabled) {
            refreshCommands()
            out.add(Line(ChatColor.GREEN, "Enabled " + p.name))
        } else {
            out.add(Line(ChatColor.RED, p.name + " failed to enable (see console)"))
        }
        return out
    }

    /** Turn an enabled plugin off (it stays loaded, so /pluginsync enable brings it back). */
    fun disable(p: Plugin, force: Boolean): List<Line> {
        val out = ArrayList<Line>()
        if (!p.isEnabled) {
            out.add(Line(ChatColor.YELLOW, p.name + " is already disabled"))
            return out
        }
        val dependents = enabledDependents(p)
        if (dependents.isNotEmpty() && !force) {
            out.add(blockedBy(p, dependents))
            return out
        }
        Bukkit.getPluginManager().disablePlugin(p)
        removeCommands(p)
        refreshCommands()
        out.add(Line(ChatColor.GOLD, "Disabled " + p.name))
        if (dependents.isNotEmpty()) {
            out.add(forcedWarning(dependents))
        }
        return out
    }

    /** Disable then enable the same plugin instance (no jar re-read, no new classes). */
    fun restart(p: Plugin, force: Boolean): List<Line> {
        val out = ArrayList<Line>()
        val pm = Bukkit.getPluginManager()
        val dependents = enabledDependents(p)
        if (p.isEnabled && dependents.isNotEmpty() && !force) {
            out.add(blockedBy(p, dependents))
            return out
        }
        if (p.isEnabled) {
            pm.disablePlugin(p)
            removeCommands(p)
        }
        pm.enablePlugin(p)
        refreshCommands()
        if (p.isEnabled) {
            out.add(Line(ChatColor.GREEN, "Restarted " + p.name))
        } else {
            out.add(Line(ChatColor.RED, p.name + " failed to enable again (see console)"))
        }
        if (dependents.isNotEmpty()) {
            out.add(forcedWarning(dependents))
        }
        return out
    }

    /** Unload the plugin and load it again from its jar, so a replaced jar takes effect. */
    fun reload(p: Plugin, force: Boolean): List<Line> {
        val out = ArrayList<Line>()
        if (p === self) {
            return updateSelf()
        }
        val dependents = enabledDependents(p)
        if (dependents.isNotEmpty() && !force) {
            out.add(blockedBy(p, dependents))
            return out
        }
        val jars = findJars(p.name)
        if (jars.isEmpty()) {
            out.add(Line(ChatColor.RED, "Can't reload " + p.name + ": no jar with that plugin name in the plugins folder"))
            return out
        }
        if (jars.size > 1) {
            out.add(Line(ChatColor.YELLOW, "There are " + jars.size + " jars for " + p.name +
                ". Pick which one to use with /pluginsync choose " + p.name,
                "/pluginsync choose " + p.name))
            return out
        }
        val jar = jars[0]
        if (jar.paperOnly) {
            out.add(Line(ChatColor.RED, "Can't reload " + p.name +
                ": its jar only has a paper-plugin.yml, which can't be loaded at runtime"))
            return out
        }
        try {
            unload(p)
            out.add(Line(ChatColor.GOLD, "Unloaded " + p.name))
        } catch (t: Throwable) {
            self.logger.log(Level.WARNING, "Failed to unload " + p.name, t)
            out.add(Line(ChatColor.RED, "Failed to unload " + p.name + ": " + describe(t)))
            return out
        }
        loadAndCheck(jar, out)
        if (dependents.isNotEmpty()) {
            out.add(forcedWarning(dependents))
        }
        return out
    }

    private fun enabledDependents(p: Plugin): List<String> {
        val names = ArrayList<String>()
        for (q in Bukkit.getPluginManager().plugins) {
            if (q !== p && q.isEnabled && dependsOn(q, p, false)) {
                names.add(q.name)
            }
        }
        return names
    }

    /** Every jar in the plugins folder whose plugin.yml name matches. */
    private fun findJars(pluginName: String): List<JarInfo> {
        val dir = self.dataFolder.absoluteFile.parentFile
        val matches = ArrayList<JarInfo>()
        for (info in listJars(dir)) {
            if (info.name.equals(pluginName, ignoreCase = true)) {
                matches.add(info)
            }
        }
        return matches
    }

    // ------------------------------------------------------------- self update

    /**
     * /pluginsync update and /pluginsync reload PluginSync: switch PluginSync to its newest jar.
     * With several PluginSync jars in the folder the most recently modified one is used.
     */
    fun updateSelf(): List<Line> {
        val out = ArrayList<Line>()
        val jars = findJars(self.name)
        if (jars.isEmpty()) {
            out.add(Line(ChatColor.RED, "Can't find a " + self.name + " jar in the plugins folder"))
            return out
        }
        var newest = jars[0]
        for (j in jars) {
            if (j.file.lastModified() > newest.file.lastModified()) {
                newest = j
            }
        }
        if (jars.size > 1) {
            out.add(Line(ChatColor.GRAY, "Several " + self.name +
                " jars found; using the most recently modified: " + newest.file.name))
            out.addAll(choose(newest, jars, false))
        } else {
            selfReload(newest, out)
        }
        return out
    }

    /**
     * Replace the running PluginSync with [target]. This is delicate because the code doing
     * the work belongs to the plugin being replaced, so:
     * all of this plugin's classes were loaded when it started (see PluginSyncPlugin), the old
     * class loader is only closed at the very end, and if the new jar fails to load the previous
     * version is loaded again.
     */
    private fun selfReload(target: JarInfo, out: MutableList<Line>): Boolean {
        val pm = Bukkit.getPluginManager()
        val name = self.name

        if (target.paperOnly || !target.hasMainClass()) {
            out.add(Line(ChatColor.RED, target.file.name +
                " doesn't look like a loadable plugin jar (main class missing); keeping the current version"))
            return false
        }

        val oldVersion = versionOf(self)
        var fallback: JarInfo? = null
        for (j in findJars(name)) {
            if (j.file != target.file && oldVersion != null && oldVersion == j.version) {
                fallback = j
            }
        }

        out.add(Line(ChatColor.GOLD, "Reloading $name ($oldVersion -> ${target.version})"))
        closeOpenMenus()

        val oldLoader: ClassLoader?
        try {
            oldLoader = unload(self, false)
        } catch (t: Throwable) {
            self.logger.log(Level.WARNING, "Failed to unload $name for self-update", t)
            out.add(Line(ChatColor.RED, "Failed to unload $name: " + describe(t)))
            return false
        }

        loadJar(target, out)
        var now = pm.getPlugin(name)
        val ok = now != null && now.isEnabled
        if (!ok && fallback != null) {
            out.add(Line(ChatColor.YELLOW, "The new jar didn't load; restoring the previous version (" +
                fallback.file.name + ")"))
            loadJar(fallback, out)
            now = pm.getPlugin(name)
        }
        refreshCommands()

        if (oldLoader is Closeable) {
            try {
                oldLoader.close()
            } catch (ignored: Throwable) {
                // the old jar just stays open until restart
            }
        }
        if (now == null || !now.isEnabled) {
            out.add(Line(ChatColor.RED, "$name is not loaded right now. Put a working $name" +
                " jar in the plugins folder and restart the server."))
        }
        return ok
    }

    // ---------------------------------------------------------------- unload

    private fun unload(plugin: Plugin) {
        unload(plugin, true)
    }

    /**
     * Fully forgets a plugin. With [closeLoader] false the plugin's class loader is left
     * open and returned, so the caller can close it later (used when PluginSync replaces itself:
     * its own code must keep working until the new version is in).
     */
    private fun unload(plugin: Plugin, closeLoader: Boolean): ClassLoader? {
        val pm = Bukkit.getPluginManager()
        val loader: ClassLoader? = plugin.javaClass.classLoader
        var pluginLoader: Any? = null
        try {
            pluginLoader = plugin.pluginLoader
        } catch (ignored: Throwable) {
            // some server builds don't expose it
        }

        pm.disablePlugin(plugin)   // cancels tasks, listeners, services, channels
        removeCommands(plugin)

        // Bukkit has no API for forgetting a plugin, so remove it from the manager's internal
        // plugin list / name lookup by reflection. Paper keeps these in an inner "instanceManager".
        purge(pm, plugin)
        purge(readField(pm, "instanceManager"), plugin)
        if (pluginLoader != null) {
            purge(pluginLoader, loader)   // the loader's registry of plugin class loaders
        }
        // Paper keeps its own records behind the Bukkit manager; without clearing them the plugin
        // can't be loaded again ("attempted to add duplicate plugin identifier").
        val cleared = PaperRegistry.forget(pm, plugin, plugin.name, loader)
        if (cleared > 0) {
            self.logger.info("Cleared $cleared Paper registry entries for ${plugin.name}")
        }

        if (closeLoader && loader is Closeable) {
            loader.close()  // releases the jar file (matters on Windows)
        }
        return loader
    }

    private fun cleanup(plugin: Plugin) {
        try {
            unload(plugin)
        } catch (t: Throwable) {
            self.logger.log(Level.WARNING, "Cleanup of " + plugin.name + " failed", t)
        }
    }

    private fun removeCommands(plugin: Plugin) {
        val commandMap = commandMap() ?: return
        val known = knownCommands(commandMap) ?: return
        val keys = ArrayList<String>()
        for ((key, c) in known) {
            if (c is PluginCommand && c.plugin === plugin) {
                keys.add(key)
            }
        }
        for (key in keys) {
            val removed = known.remove(key)
            if (removed != null && commandMap is CommandMap) {
                removed.unregister(commandMap)
            }
        }
    }

    // ----------------------------------------------------------------- misc

    /** Tell the server and connected players about added / removed commands (1.13+ only). */
    private fun refreshCommands() {
        try {
            val server = Bukkit.getServer()
            server.javaClass.getMethod("syncCommands").invoke(server)
        } catch (ignored: Throwable) {
            // not available on this server build
        }
        for (player in Bukkit.getOnlinePlayers()) {
            try {
                player.javaClass.getMethod("updateCommands").invoke(player)
            } catch (ignored: Throwable) {
                // pre-1.13: clients get no command tree to refresh
            }
        }
    }

    companion object {

        private fun blockedBy(p: Plugin, dependents: List<String>): Line =
            Line(ChatColor.RED, "Not touching " + p.name + ": " + dependents.joinToString(", ") +
                " depend(s) on it. Add -f to do it anyway.")

        private fun forcedWarning(dependents: List<String>): Line =
            Line(ChatColor.YELLOW, "Warning: " + dependents.joinToString(", ") +
                " depend(s) on this plugin and may misbehave until restarted.")

        /** Every readable plugin jar directly inside [dir], sorted by file name. */
        private fun listJars(dir: File): List<JarInfo> {
            val jars = dir.listFiles(FilenameFilter { _, n -> n.lowercase(Locale.ROOT).endsWith(".jar") })
            val out = ArrayList<JarInfo>()
            if (jars == null) {
                return out
            }
            jars.sort()
            for (jar in jars) {
                val info = JarInfo.read(jar)
                if (info != null) {
                    out.add(info)   // jars with no plugin.yml (libraries etc.) are skipped
                }
            }
            return out
        }

        /** Close any open choice menu: its listener belongs to the version that is going away. */
        private fun closeOpenMenus() {
            for (player in Bukkit.getOnlinePlayers()) {
                try {
                    // InventoryView is a class on old servers and an interface on new ones, so reflect.
                    val view = player.javaClass.getMethod("getOpenInventory").invoke(player)
                    val top = Class.forName("org.bukkit.inventory.InventoryView")
                        .getMethod("getTopInventory").invoke(view)
                    if (top is Inventory && top.holder is ChoiceMenu.Holder) {
                        player.closeInventory()
                    }
                } catch (ignored: Throwable) {
                    // best effort
                }
            }
        }

        /** CraftServer#getCommandMap exists on every CraftBukkit/Spigot/Paper version. */
        private fun commandMap(): Any? = try {
            val server = Bukkit.getServer()
            server.javaClass.getMethod("getCommandMap").invoke(server)
        } catch (t: Throwable) {
            null
        }

        /** CommandMap#getKnownCommands exists on 1.13+; older servers keep a protected field. */
        @Suppress("UNCHECKED_CAST")
        private fun knownCommands(commandMap: Any): MutableMap<String, Command>? {
            try {
                val m = commandMap.javaClass.getMethod("getKnownCommands")
                return m.invoke(commandMap) as MutableMap<String, Command>
            } catch (ignored: Throwable) {
                // fall through to field access
            }
            var c: Class<*>? = commandMap.javaClass
            while (c != null && c != Any::class.java) {
                try {
                    val f = c.getDeclaredField("knownCommands")
                    f.isAccessible = true
                    return f.get(commandMap) as MutableMap<String, Command>
                } catch (ignored: Throwable) {
                    // try superclass
                }
                c = c.superclass
            }
            return null
        }

        /** Removes [victim] from every list / map field of [target]. */
        @Suppress("UNCHECKED_CAST")
        private fun purge(target: Any?, victim: Any?) {
            if (target == null || victim == null) {
                return
            }
            var c: Class<*>? = target.javaClass
            while (c != null && c != Any::class.java) {
                for (f in c.declaredFields) {
                    if (Modifier.isStatic(f.modifiers)) {
                        continue
                    }
                    if (!List::class.java.isAssignableFrom(f.type) && !Map::class.java.isAssignableFrom(f.type)) {
                        continue
                    }
                    try {
                        f.isAccessible = true
                        val value = f.get(target)
                        if (value is List<*>) {
                            (value as MutableList<Any?>).remove(victim)
                        } else if (value is Map<*, *>) {
                            val it = (value as MutableMap<Any?, Any?>).values.iterator()
                            while (it.hasNext()) {
                                if (it.next() === victim) {
                                    it.remove()
                                }
                            }
                        }
                    } catch (ignored: Throwable) {
                        // immutable or inaccessible; not the field we need
                    }
                }
                c = c.superclass
            }
        }

        private fun readField(target: Any, name: String): Any? {
            var c: Class<*>? = target.javaClass
            while (c != null && c != Any::class.java) {
                try {
                    val f = c.getDeclaredField(name)
                    f.isAccessible = true
                    return f.get(target)
                } catch (ignored: Throwable) {
                    // try superclass
                }
                c = c.superclass
            }
            return null
        }

        @Suppress("DEPRECATION")
        private fun dependsOn(q: Plugin, p: Plugin, includeSoft: Boolean): Boolean {
            return try {
                if (containsIgnoreCase(q.description.depend, p.name)) {
                    true
                } else {
                    includeSoft && containsIgnoreCase(q.description.softDepend, p.name)
                }
            } catch (t: Throwable) {
                false
            }
        }

        /** Running version: getDescription() on most servers, getPluginMeta() if that is all there is. */
        @Suppress("DEPRECATION")
        fun versionOf(p: Plugin): String? {
            try {
                return p.description.version
            } catch (ignored: Throwable) {
                // fall through
            }
            return try {
                val meta = p.javaClass.getMethod("getPluginMeta").invoke(p)
                val metaType = Class.forName("io.papermc.paper.plugin.configuration.PluginMeta")
                metaType.getMethod("getVersion").invoke(meta).toString()
            } catch (ignored: Throwable) {
                null
            }
        }

        private fun containsIgnoreCase(list: List<String>?, name: String): Boolean {
            if (list == null) {
                return false
            }
            for (s in list) {
                if (s.equals(name, ignoreCase = true)) {
                    return true
                }
            }
            return false
        }

        /** Order jars so that anything a jar depends on (among the new jars) loads first. */
        private fun order(jars: List<JarInfo>): List<JarInfo> {
            val byName = LinkedHashMap<String, JarInfo>()
            for (j in jars) {
                byName[lower(j.name)] = j
            }
            val out = ArrayList<JarInfo>()
            val done = HashSet<String>()
            val visiting = HashSet<String>()
            for (j in jars) {
                visit(j, byName, done, visiting, out)
            }
            return out
        }

        private fun visit(
            j: JarInfo, byName: Map<String, JarInfo>, done: MutableSet<String>,
            visiting: MutableSet<String>, out: MutableList<JarInfo>
        ) {
            val key = lower(j.name)
            if (done.contains(key) || !visiting.add(key)) {
                return
            }
            for (dep in j.deps) {
                val d = byName[lower(dep)]
                if (d != null) {
                    visit(d, byName, done, visiting, out)
                }
            }
            visiting.remove(key)
            done.add(key)
            out.add(j)
        }

        private fun locationOf(p: Plugin): Path? {
            return try {
                val source = p.javaClass.protectionDomain.codeSource ?: return null
                Paths.get(source.location.toURI()).toAbsolutePath().normalize()
            } catch (e: Exception) {
                null
            }
        }

        /** "Exception: message", plus the root cause when it differs. */
        private fun describe(t: Throwable): String {
            var root = t
            var i = 0
            while (i < 10 && root.cause != null && root.cause !== root) {
                root = root.cause!!
                i++
            }
            var text = summary(t)
            if (root !== t) {
                text += " (cause: " + summary(root) + ")"
            }
            return text
        }

        private fun summary(t: Throwable): String {
            val msg = t.message
            return t.javaClass.simpleName + (if (msg == null) "" else ": $msg")
        }

        private fun lower(s: String): String = s.lowercase(Locale.ROOT)
    }
}
