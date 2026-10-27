package dev.etran;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/** A chest-style menu listing the competing jars of one plugin; click one to use it. */
final class ChoiceMenu implements Listener {

    /** Marks inventories that belong to this menu and remembers what each slot means. */
    static final class Holder implements InventoryHolder {
        final String pluginName;
        final List<JarInfo> options;
        private Inventory inventory;

        Holder(String pluginName, List<JarInfo> options) {
            this.pluginName = pluginName;
            this.options = options;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final int MAX_SLOTS = 54;

    private final PluginSyncPlugin plugin;

    ChoiceMenu(PluginSyncPlugin plugin) {
        this.plugin = plugin;
    }

    void open(Player player, String pluginName, List<JarInfo> options, JarInfo runningJar) {
        int count = Math.min(options.size(), MAX_SLOTS);
        int size = Math.max(9, ((count + 8) / 9) * 9);
        Holder holder = new Holder(pluginName, new ArrayList<JarInfo>(options.subList(0, count)));

        // 1.8 rejects inventory titles longer than 32 characters.
        String title = "Choose: " + pluginName;
        if (title.length() > 32) {
            title = title.substring(0, 32);
        }
        Inventory inventory = Bukkit.createInventory(holder, size, title);
        holder.inventory = inventory;

        for (int i = 0; i < count; i++) {
            JarInfo jar = options.get(i);
            ItemStack item = new ItemStack(Material.PAPER);
            ItemMeta meta = item.getItemMeta();
            meta.setDisplayName(ChatColor.GREEN + jar.file.getName());
            List<String> lore = new ArrayList<String>();
            lore.add(ChatColor.GRAY + "Version: " + ChatColor.WHITE + jar.version);
            if (jar == runningJar) {
                lore.add(ChatColor.YELLOW + "Currently running");
                lore.add(ChatColor.GRAY + "Click to keep this one");
            } else {
                lore.add(ChatColor.GRAY + "Click to use this one");
            }
            meta.setLore(lore);
            item.setItemMeta(meta);
            inventory.setItem(i, item);
        }
        player.openInventory(inventory);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getInventory();
        if (!(top.getHolder() instanceof Holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        final Player player = (Player) event.getWhoClicked();
        int slot = event.getRawSlot();
        final Holder holder = (Holder) top.getHolder();
        if (slot < 0 || slot >= top.getSize() || slot >= holder.options.size()) {
            return;
        }
        if (!player.hasPermission("pluginsync.use")) {
            return;
        }
        final JarInfo chosen = holder.options.get(slot);
        player.closeInventory();
        // Run after the click has been fully processed.
        Bukkit.getScheduler().runTask(plugin, new Runnable() {
            @Override
            public void run() {
                plugin.applyChoice(player, holder.pluginName, chosen, false);
            }
        });
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }
}
