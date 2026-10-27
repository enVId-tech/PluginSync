package dev.etran;

import org.bukkit.entity.Player;

import java.lang.reflect.Method;

/** Clickable chat lines, done by reflection so the jar still loads on servers without them. */
final class Chat {

    private Chat() {
    }

    /**
     * Sends {@code legacyText} (with section-sign color codes) as a chat line that runs
     * {@code command} when clicked. Returns false if this server can't do that, in which case
     * the caller should send plain text instead.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static boolean sendClickable(Player player, String legacyText, String command) {
        try {
            Class<?> textComponent = Class.forName("net.md_5.bungee.api.chat.TextComponent");
            Class<?> baseComponent = Class.forName("net.md_5.bungee.api.chat.BaseComponent");
            Class<?> clickEvent = Class.forName("net.md_5.bungee.api.chat.ClickEvent");
            Class<?> actionType = Class.forName("net.md_5.bungee.api.chat.ClickEvent$Action");

            Object[] parts = (Object[]) textComponent.getMethod("fromLegacyText", String.class)
                    .invoke(null, legacyText);
            Object click = clickEvent.getConstructor(actionType, String.class)
                    .newInstance(Enum.valueOf((Class<Enum>) actionType, "RUN_COMMAND"), command);
            Method setClick = baseComponent.getMethod("setClickEvent", clickEvent);
            for (Object part : parts) {
                setClick.invoke(part, click);
            }

            Object spigot = Player.class.getMethod("spigot").invoke(player);
            Class<?> spigotType = Class.forName("org.bukkit.entity.Player$Spigot");
            spigotType.getMethod("sendMessage", parts.getClass()).invoke(spigot, new Object[]{parts});
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
