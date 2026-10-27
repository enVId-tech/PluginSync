package dev.etran;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public final class PluginSyncPlugin extends JavaPlugin implements TabExecutor {

    private Syncer syncer;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        syncer = new Syncer(this);
        var command = getCommand("pluginsync");
        if (command != null) {
            command.setExecutor(this);
            command.setTabCompleter(this);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("pluginsync.use")) {
            send(sender, NamedTextColor.RED, "You don't have permission to do that.");
            return true;
        }

        String sub = args.length == 0 ? "sync" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "scan" -> {
                reloadConfig();
                Syncer.Plan plan = syncer.scan();
                if (!plan.hasChanges()) {
                    send(sender, NamedTextColor.GREEN, "Nothing to do: the plugins folder matches what's loaded.");
                } else {
                    if (!plan.toLoad().isEmpty()) {
                        send(sender, NamedTextColor.AQUA, "Would load: " + plan.toLoad().stream()
                                .map(JarInfo::name).collect(Collectors.joining(", ")));
                    }
                    if (!plan.toUnload().isEmpty()) {
                        send(sender, NamedTextColor.AQUA, "Would unload: " + plan.toUnload().stream()
                                .map(Plugin::getName).collect(Collectors.joining(", ")));
                    }
                    send(sender, NamedTextColor.GRAY, "Run /" + label + " to apply.");
                }
                plan.notes().forEach(l -> send(sender, l.color(), l.text()));
            }
            case "sync" -> {
                reloadConfig();
                Syncer.Plan plan = syncer.scan();
                if (!plan.hasChanges()) {
                    send(sender, NamedTextColor.GREEN, "Nothing to do: the plugins folder matches what's loaded.");
                    plan.notes().forEach(l -> send(sender, l.color(), l.text()));
                    return true;
                }
                syncer.apply(plan).forEach(l -> send(sender, l.color(), l.text()));
            }
            default -> send(sender, NamedTextColor.YELLOW, "Usage: /" + label + " [scan|sync]");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1 && sender.hasPermission("pluginsync.use")) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return List.of("scan", "sync").stream().filter(s -> s.startsWith(prefix)).toList();
        }
        return List.of();
    }

    private void send(CommandSender sender, NamedTextColor color, String text) {
        sender.sendMessage(Component.text(text, color));
        if (!(sender instanceof ConsoleCommandSender)) {
            getLogger().info("[" + sender.getName() + "] " + text);
        }
    }
}
