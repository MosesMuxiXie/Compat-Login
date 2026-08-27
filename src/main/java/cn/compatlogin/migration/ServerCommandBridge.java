package cn.compatlogin.migration;

import cn.compatlogin.CompatLogin;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

final class ServerCommandBridge {
    private ServerCommandBridge() {
    }

    /**
     * Runs a command through the vanilla dispatcher. On Minecraft 1.19+ {@code performPrefixedCommand}
     * returns void, so the result no longer reports whether the command actually worked; treat the
     * return value as best-effort only. Player messaging and disconnects therefore use the direct
     * APIs in this class instead of commands.
     */
    static boolean execute(MinecraftServer server, String command) {
        Object commands = server.getCommands();
        Object source = server.createCommandSourceStack();
        for (Method method : commands.getClass().getMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (parameters.length != 2
                || parameters[1] != String.class
                || !parameters[0].isInstance(source)) {
                continue;
            }
            try {
                Object result = method.invoke(commands, source, command);
                return !(result instanceof Number) || ((Number) result).intValue() > 0;
            } catch (IllegalAccessException exception) {
                CompatLogin.LOGGER.error("Cannot access the Minecraft command dispatcher", exception);
                return false;
            } catch (InvocationTargetException exception) {
                Throwable cause = exception.getCause() == null ? exception : exception.getCause();
                CompatLogin.LOGGER.error("Server command failed: /{}", command, cause);
                return false;
            }
        }
        CompatLogin.LOGGER.error("No compatible Minecraft command execution method was found");
        return false;
    }

    /**
     * Disconnects a player through the direct connection API. Whether the disconnect actually
     * completed is verified asynchronously by the migration state machine (the target must be
     * observed offline before any player data is touched).
     */
    static void disconnect(MinecraftServer server, ServerPlayer player, String reason) {
        player.connection.disconnect(MinecraftTextBridge.literal(reason));
    }

    static void reply(CommandSourceStack source, String message) {
        Entity entity = source.getEntity();
        if (entity instanceof ServerPlayer) {
            tell((ServerPlayer) entity, message);
        } else {
            CompatLogin.LOGGER.info("[account migrate] {}", message);
        }
    }

    static void tell(ServerPlayer player, String message) {
        Component component;
        try {
            component = MinecraftTextBridge.literal(message);
        } catch (RuntimeException | LinkageError failure) {
            CompatLogin.LOGGER.warn(
                "Cannot build a chat message for {}; the migration notice was dropped",
                player.getUUID(),
                failure
            );
            return;
        }
        if (!VersionBridge.sendSystemMessage(player, component)) {
            CompatLogin.LOGGER.warn("Could not deliver migration message to {}: {}", player.getUUID(), message);
        }
    }

    static ServerPlayer requirePlayer(CommandSourceStack source) throws CommandSyntaxException {
        return source.getPlayerOrException();
    }
}
