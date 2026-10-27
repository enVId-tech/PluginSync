package dev.etran

import org.bukkit.entity.Player

/** Clickable chat lines, done by reflection so the jar still loads on servers without them. */
object Chat {

    /**
     * Sends [legacyText] (with section-sign color codes) as a chat line that runs [command]
     * when clicked. Returns false if this server can't do that, in which case the caller should
     * send plain text instead.
     */
    fun sendClickable(player: Player, legacyText: String, command: String): Boolean {
        return try {
            val textComponent = Class.forName("net.md_5.bungee.api.chat.TextComponent")
            val baseComponent = Class.forName("net.md_5.bungee.api.chat.BaseComponent")
            val clickEvent = Class.forName("net.md_5.bungee.api.chat.ClickEvent")
            val actionType = Class.forName("net.md_5.bungee.api.chat.ClickEvent\$Action")

            val parts = textComponent.getMethod("fromLegacyText", String::class.java)
                .invoke(null, legacyText) as Array<*>
            val runCommand = actionType.enumConstants.first { (it as Enum<*>).name == "RUN_COMMAND" }
            val click = clickEvent.getConstructor(actionType, String::class.java).newInstance(runCommand, command)
            val setClick = baseComponent.getMethod("setClickEvent", clickEvent)
            for (part in parts) {
                setClick.invoke(part, click)
            }

            val spigot = Player::class.java.getMethod("spigot").invoke(player)
            val spigotType = Class.forName("org.bukkit.entity.Player\$Spigot")
            // The whole component array is one argument (not spread into several).
            spigotType.getMethod("sendMessage", parts.javaClass).invoke(spigot, parts)
            true
        } catch (t: Throwable) {
            false
        }
    }
}
