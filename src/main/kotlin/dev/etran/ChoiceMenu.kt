package dev.etran

import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack

/** A chest-style menu listing the competing jars of one plugin; click one to use it. */
class ChoiceMenu(private val plugin: PluginSyncPlugin) : Listener {

    /** Marks inventories that belong to this menu and remembers what each slot means. */
    class Holder(val pluginName: String, val options: List<JarInfo>) : InventoryHolder {
        lateinit var inv: Inventory

        override fun getInventory(): Inventory = inv
    }

    fun open(player: Player, pluginName: String, options: List<JarInfo>, runningJar: JarInfo?) {
        val count = minOf(options.size, MAX_SLOTS)
        val size = maxOf(9, ((count + 8) / 9) * 9)
        val holder = Holder(pluginName, ArrayList(options.subList(0, count)))

        // 1.8 rejects inventory titles longer than 32 characters.
        var title = "Choose: $pluginName"
        if (title.length > 32) {
            title = title.substring(0, 32)
        }
        val inventory = Bukkit.createInventory(holder, size, title)
        holder.inv = inventory

        for (i in 0 until count) {
            val jar = options[i]
            val item = ItemStack(Material.PAPER)
            val meta = item.itemMeta
            meta.displayName = ChatColor.GREEN.toString() + jar.file.name
            val lore = ArrayList<String>()
            lore.add(ChatColor.GRAY.toString() + "Version: " + ChatColor.WHITE + jar.version)
            if (jar === runningJar) {
                lore.add(ChatColor.YELLOW.toString() + "Currently running")
                lore.add(ChatColor.GRAY.toString() + "Click to keep this one")
            } else {
                lore.add(ChatColor.GRAY.toString() + "Click to use this one")
            }
            meta.lore = lore
            item.itemMeta = meta
            inventory.setItem(i, item)
        }
        player.openInventory(inventory)
    }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val top = event.inventory
        val holder = top.holder as? Holder ?: return
        event.isCancelled = true
        val player = event.whoClicked as? Player ?: return
        val slot = event.rawSlot
        if (slot < 0 || slot >= top.size || slot >= holder.options.size) {
            return
        }
        if (!player.hasPermission("pluginsync.use")) {
            return
        }
        val chosen = holder.options[slot]
        player.closeInventory()
        // Run after the click has been fully processed.
        Bukkit.getScheduler().runTask(plugin, Runnable {
            plugin.applyChoice(player, holder.pluginName, chosen, false)
        })
    }

    @EventHandler
    fun onDrag(event: InventoryDragEvent) {
        if (event.inventory.holder is Holder) {
            event.isCancelled = true
        }
    }

    companion object {
        private const val MAX_SLOTS = 54
    }
}
