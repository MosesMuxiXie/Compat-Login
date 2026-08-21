package cn.compatlogin.migration;

import cn.compatlogin.CompatLogin;
import cn.compatlogin.auth.AuthlibProfileAdapter;
import com.google.gson.Gson;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

final class ServerCommandBridge {
    private static final Gson GSON = new Gson();

    private ServerCommandBridge() {
    }

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

    static void reply(CommandSourceStack source, String message) {
        Entity entity = source.getEntity();
        if (entity instanceof ServerPlayer) {
            tell(
                source.getServer(),
                AuthlibProfileAdapter.readProfileName(((ServerPlayer) entity).getGameProfile()),
                message
            );
        } else {
            CompatLogin.LOGGER.info("[account migrate] {}", message);
        }
    }

    static void tell(MinecraftServer server, String playerName, String message) {
        String component = "{\"text\":" + GSON.toJson(message) + ",\"color\":\"yellow\"}";
        if (!execute(server, "tellraw " + playerName + " " + component)) {
            CompatLogin.LOGGER.warn("Could not deliver migration message to {}: {}", playerName, message);
        }
    }

    static ServerPlayer requirePlayer(CommandSourceStack source) throws CommandSyntaxException {
        return source.getPlayerOrException();
    }
}
