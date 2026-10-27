package dev.etran

import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.command.TabExecutor
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.util.Locale
import java.util.jar.JarFile

class PluginSyncPlugin : JavaPlugin(), TabExecutor {

    private lateinit var syncer: Syncer
    private lateinit var menu: ChoiceMenu

    override fun onEnable() {
        // Load every class of this jar now. Self-update replaces this plugin while its own code is
        // running, and the jar on disk may already have been overwritten by then; classes that are
        // already in memory keep working either way.
        preloadOwnClasses()
        saveDefaultConfig()
        syncer = Syncer(this)
        menu = ChoiceMenu(this)
        server.pluginManager.registerEvents(menu, this)
        val command = getCommand("pluginsync")
        if (command != null) {
            command.executor = this
            command.tabCompleter = this
        }
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        if (!sender.hasPermission("pluginsync.use")) {
            send(sender, ChatColor.RED, "You don't have permission to do that.")
            return true
        }

        val sub = if (args.isEmpty()) "sync" else args[0].lowercase(Locale.ROOT)
        when {
            sub == "scan" -> scan(sender, label)
            sub == "sync" -> sync(sender, label)
            sub == "choose" -> choose(sender, label, args)
            sub == "update" -> {
                for (line in syncer.updateSelf()) {
                    send(sender, line)
                }
            }
            PLUGIN_ACTIONS.contains(sub) -> pluginAction(sender, label, sub, args)
            else -> usage(sender, label)
        }
        return true
    }

    // ------------------------------------------------------------ scan / sync

    private fun scan(sender: CommandSender, label: String) {
        reloadConfig()
        val plan = syncer.scan()
        if (!plan.hasChanges()) {
            send(sender, ChatColor.GREEN, "Nothing to load or unload: the plugins folder matches what's loaded.")
        } else {
            if (plan.toLoad.isNotEmpty()) {
                send(sender, ChatColor.AQUA, "Would load: " + plan.toLoad.joinToString(", ") { it.name })
            }
            if (plan.toUnload.isNotEmpty()) {
                send(sender, ChatColor.AQUA, "Would unload: " + plan.toUnload.joinToString(", ") { it.name })
            }
            send(sender, ChatColor.GRAY, "Run /$label to apply.")
        }
        printConflicts(sender, label, plan)
        for (line in plan.notes) {
            send(sender, line)
        }
    }

    private fun sync(sender: CommandSender, label: String) {
        reloadConfig()
        val plan = syncer.scan()
        if (!plan.hasChanges()) {
            send(sender, ChatColor.GREEN, "Nothing to load or unload: the plugins folder matches what's loaded.")
            printConflicts(sender, label, plan)
            for (line in plan.notes) {
                send(sender, line)
            }
            return
        }
        for (line in syncer.applyPlan(plan)) {
            send(sender, line)
        }
        printConflicts(sender, label, plan)
    }

    /** One line per plugin that has several jars, clickable for players. */
    private fun printConflicts(sender: CommandSender, label: String, plan: Syncer.Plan) {
        for ((name, jars) in plan.conflicts) {
            val described = jars.joinToString(", ") { it.file.name + " (" + it.version + ")" }
            send(sender, Syncer.Line(ChatColor.YELLOW,
                "Multiple jars for $name: $described. Choose one: /$label choose $name",
                "/$label choose $name"))
        }
    }

    // ----------------------------------------------------------------- choose

    /** /pluginsync choose [plugin] [number|gui] [-f] */
    private fun choose(sender: CommandSender, label: String, args: Array<String>) {
        var force = false
        val tokens = ArrayList<String>()
        for (i in 1 until args.size) {
            if (args[i].equals("-f", ignoreCase = true) || args[i].equals("--force", ignoreCase = true)) {
                force = true
            } else {
                tokens.add(args[i])
            }
        }

        reloadConfig()
        val plan = syncer.scan()

        if (tokens.isEmpty()) {
            if (plan.conflicts.isEmpty()) {
                send(sender, ChatColor.GREEN, "No plugin has more than one jar in the plugins folder right now.")
            }
            for ((name, jars) in plan.conflicts) {
                showOptions(sender, label, name, jars)
            }
            return
        }

        val candidates = plan.conflictsFor(tokens[0])
        if (candidates == null) {
            send(sender, ChatColor.RED, "There's only one jar (or none) for " + tokens[0] +
                ". To re-read a replaced jar use /" + label + " reload " + tokens[0] + ".")
            return
        }
        val name = candidates[0].name

        if (tokens.size == 1) {
            showOptions(sender, label, name, candidates)
            return
        }

        val pick = tokens[1]
        if (pick.equals("gui", ignoreCase = true)) {
            if (sender !is Player) {
                send(sender, ChatColor.RED, "The menu is only for players. Use a number instead: /" +
                    label + " choose " + name + " <number>")
                return
            }
            val running = Bukkit.getPluginManager().getPlugin(name)
            val runningJar = if (running == null) null else syncer.runningJar(running, candidates)
            menu.open(sender, name, candidates, runningJar)
            return
        }

        val index = pick.toIntOrNull()
        if (index == null) {
            send(sender, ChatColor.YELLOW, "Usage: /$label choose $name <number|gui> [-f]")
            return
        }
        if (index < 1 || index > candidates.size) {
            send(sender, ChatColor.RED, "Pick a number between 1 and " + candidates.size + ".")
            return
        }
        applyChoice(sender, name, candidates[index - 1], force)
    }

    private fun showOptions(sender: CommandSender, label: String, name: String, candidates: List<JarInfo>) {
        val running = Bukkit.getPluginManager().getPlugin(name)
        val runningJar = if (running == null) null else syncer.runningJar(running, candidates)
        val state = if (running == null) "not loaded" else "running " + Syncer.versionOf(running).toString()
        send(sender, ChatColor.AQUA, "Multiple jars for $name ($state):")
        for (i in candidates.indices) {
            val j = candidates[i]
            val isRunning = j === runningJar
            send(sender, Syncer.Line(if (isRunning) ChatColor.YELLOW else ChatColor.GREEN,
                "  [" + (i + 1) + "] " + j.file.name + "  v" + j.version + (if (isRunning) "  (running)" else ""),
                "/$label choose $name " + (i + 1)))
        }
        if (sender is Player) {
            send(sender, Syncer.Line(ChatColor.AQUA, "  [Open menu]", "/$label choose $name gui"))
        }
        send(sender, ChatColor.GRAY, "Click a line, or run /$label choose $name <number>. " +
            "Add -f if other plugins depend on it.")
    }

    /** Re-checks the folder (it may have changed since the list was shown), then applies the pick. */
    fun applyChoice(sender: CommandSender, name: String, requested: JarInfo, force: Boolean) {
        val plan = syncer.scan()
        val candidates = plan.conflictsFor(name)
        if (candidates == null) {
            send(sender, ChatColor.RED, "There's no longer more than one jar for $name" +
                ". Use /pluginsync reload $name to re-read its jar.")
            return
        }
        var chosen: JarInfo? = null
        for (j in candidates) {
            if (j.file.absoluteFile == requested.file.absoluteFile) {
                chosen = j
                break
            }
        }
        if (chosen == null) {
            send(sender, ChatColor.RED, requested.file.name + " is no longer in the plugins folder.")
            return
        }
        for (line in syncer.choose(chosen, candidates, force)) {
            send(sender, line)
        }
    }

    // --------------------------------------------- enable/disable/restart/reload

    /** /pluginsync enable|disable|restart|reload <plugin> [-f] */
    private fun pluginAction(sender: CommandSender, label: String, action: String, args: Array<String>) {
        var name: String? = null
        var force = false
        for (i in 1 until args.size) {
            if (args[i].equals("-f", ignoreCase = true) || args[i].equals("--force", ignoreCase = true)) {
                force = true
            } else if (name == null) {
                name = args[i]
            }
        }
        if (name == null) {
            send(sender, ChatColor.YELLOW, "Usage: /$label $action <plugin> [-f]")
            return
        }

        val target = Bukkit.getPluginManager().getPlugin(name)
        if (target == null) {
            send(sender, ChatColor.RED, "No loaded plugin named $name" +
                ". If you just added its jar, run /$label first.")
            return
        }
        if (target === this && action != "reload") {
            send(sender, ChatColor.RED, "PluginSync can't $action itself. " +
                "(/$label reload PluginSync or /$label update switch it to its newest jar.)")
            return
        }

        val lines = when (action) {
            "enable" -> syncer.enable(target)
            "disable" -> syncer.disable(target, force)
            "restart" -> syncer.restart(target, force)
            else -> syncer.reload(target, force)
        }
        for (line in lines) {
            send(sender, line)
        }
    }

    private fun usage(sender: CommandSender, label: String) {
        send(sender, ChatColor.YELLOW, "/$label                     load new jars, unload removed ones")
        send(sender, ChatColor.YELLOW, "/$label scan               preview what a sync would do")
        send(sender, ChatColor.YELLOW, "/$label choose [plugin]    pick between several jars of one plugin")
        send(sender, ChatColor.YELLOW, "/$label enable <plugin>    turn a disabled plugin on")
        send(sender, ChatColor.YELLOW, "/$label disable <plugin>   turn a plugin off")
        send(sender, ChatColor.YELLOW, "/$label restart <plugin>   disable, then enable")
        send(sender, ChatColor.YELLOW, "/$label reload <plugin>    unload and load again from its jar")
        send(sender, ChatColor.YELLOW, "/$label update             switch PluginSync itself to its newest jar")
        send(sender, ChatColor.GRAY, "Add -f to act even if other plugins depend on the target.")
    }

    // ---------------------------------------------------------- tab completion

    override fun onTabComplete(
        sender: CommandSender, command: Command, alias: String, args: Array<String>
    ): MutableList<String> {
        val out = ArrayList<String>()
        if (!sender.hasPermission("pluginsync.use")) {
            return out
        }
        val first = if (args.isNotEmpty()) args[0].lowercase(Locale.ROOT) else ""
        if (args.size == 1) {
            addMatches(out, SUBCOMMANDS, args[0])
        } else if (args.size == 2 && PLUGIN_ACTIONS.contains(first)) {
            val names = ArrayList<String>()
            for (p in Bukkit.getPluginManager().plugins) {
                if (p !== this || first == "reload") {
                    names.add(p.name)
                }
            }
            addMatches(out, names, args[1])
        } else if (args.size == 3 && PLUGIN_ACTIONS.contains(first)) {
            addMatches(out, listOf("-f"), args[2])
        } else if (first == "choose") {
            val plan = syncer.scan()
            if (args.size == 2) {
                addMatches(out, ArrayList(plan.conflicts.keys), args[1])
            } else if (args.size == 3) {
                val candidates = plan.conflictsFor(args[1])
                val options = ArrayList<String>()
                options.add("gui")
                if (candidates != null) {
                    for (i in 1..candidates.size) {
                        options.add(i.toString())
                    }
                }
                addMatches(out, options, args[2])
            } else if (args.size == 4) {
                addMatches(out, listOf("-f"), args[3])
            }
        }
        return out
    }

    private fun addMatches(out: MutableList<String>, options: List<String>, typed: String) {
        val prefix = typed.lowercase(Locale.ROOT)
        for (s in options) {
            if (s.lowercase(Locale.ROOT).startsWith(prefix)) {
                out.add(s)
            }
        }
    }

    private fun preloadOwnClasses() {
        try {
            val source = javaClass.protectionDomain.codeSource ?: return
            val file = File(source.location.toURI())
            if (!file.isFile) {
                return
            }
            val loader = javaClass.classLoader
            JarFile(file).use { jar ->
                val entries = jar.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement().name
                    if (entry.endsWith(".class")) {
                        try {
                            Class.forName(entry.substring(0, entry.length - 6).replace('/', '.'), false, loader)
                        } catch (ignored: Throwable) {
                            // best effort
                        }
                    }
                }
            }
        } catch (ignored: Throwable) {
            // best effort: self-update then relies on lazy class loading
        }
    }

    // ---------------------------------------------------------------- messages

    private fun send(sender: CommandSender, color: ChatColor, text: String) {
        send(sender, Syncer.Line(color, text))
    }

    private fun send(sender: CommandSender, line: Syncer.Line) {
        val legacy = "${line.color}${line.text}"
        var sent = false
        if (line.command != null && sender is Player) {
            sent = Chat.sendClickable(sender, legacy, line.command)
        }
        if (!sent) {
            sender.sendMessage(legacy)
        }
        if (sender !is ConsoleCommandSender) {
            logger.info("[" + sender.name + "] " + line.text)
        }
    }

    companion object {
        private val SUBCOMMANDS =
            listOf("scan", "sync", "choose", "update", "enable", "disable", "restart", "reload")
        private val PLUGIN_ACTIONS = listOf("enable", "disable", "restart", "reload")
    }
}
