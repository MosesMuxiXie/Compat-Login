package cn.compatlogin.migration;

import cn.compatlogin.CompatLogin;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.MappingResolver;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.function.Function;

/**
 * Bridges version-specific permission and message APIs across the three runtime worlds this mod
 * supports: intermediary-mapped 1.16 - 1.21.4 (legacy API), intermediary-mapped 1.21.5 - 1.21.11
 * (new permission API) and unmapped 26.x (official names). Every path resolves lazily on first use,
 * caches its handles, and fails closed with a one-time log line, mirroring MinecraftTextBridge.
 */
final class VersionBridge {
    // Legacy permission API: SharedSuggestionProvider.hasPermission(int), stable across 1.16 - 1.21.4.
    private static final String SHARED_SUGGESTION_PROVIDER_INTERMEDIARY = "net.minecraft.class_2172";
    private static final String HAS_PERMISSION_METHOD = "method_9259";

    // New permission API. Intermediary names are those of 1.21.11; official names are used on 26.x.
    private static final String COMMAND_SOURCE_STACK_INTERMEDIARY = "net.minecraft.class_2168";
    private static final String PERMISSIONS_FIELD = "field_63437";
    private static final String PERMISSION_SET_INTERMEDIARY = "net.minecraft.class_12096";
    private static final String ALL_PERMISSIONS_FIELD = "field_63208";
    private static final String LEVEL_BASED_PERMISSION_SET_INTERMEDIARY = "net.minecraft.class_12086";
    private static final String LEVEL_METHOD = "method_75009";
    private static final String PERMISSION_LEVEL_INTERMEDIARY = "net.minecraft.class_12094";
    private static final String ID_METHOD = "method_75026";

    // Message APIs. sendSystemMessage exists from 1.19 on, displayClientMessage covers 1.16 - 1.18.
    private static final String SERVER_PLAYER_INTERMEDIARY = "net.minecraft.class_3222";
    private static final String SEND_SYSTEM_MESSAGE_METHOD = "method_64398";
    private static final String COMPONENT_INTERMEDIARY = "net.minecraft.class_2561";
    private static final String PLAYER_INTERMEDIARY = "net.minecraft.class_1657";
    private static final String DISPLAY_CLIENT_MESSAGE_METHOD = "method_7353";

    private static final int ADMIN_LEVEL = 3;

    private static volatile Function<CommandSourceStack, Boolean> permissionCheck;
    private static volatile boolean permissionUnavailable;
    private static volatile MessageSender messageSender;
    private static volatile boolean messageUnavailable;

    private VersionBridge() {
    }

    /** Returns whether the source holds at least admin (level 3) command permissions; fails closed. */
    static boolean hasCommandPermission(CommandSourceStack source) {
        Function<CommandSourceStack, Boolean> check = permissionCheck;
        if (check == null && !permissionUnavailable) {
            synchronized (VersionBridge.class) {
                if (permissionCheck == null && !permissionUnavailable) {
                    permissionCheck = resolvePermissionCheck();
                    if (permissionCheck == null) {
                        permissionUnavailable = true;
                    }
                }
                check = permissionCheck;
            }
        }
        if (check == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(check.apply(source));
        } catch (RuntimeException | LinkageError failure) {
            CompatLogin.LOGGER.error("Cannot check command permission on this Minecraft version", failure);
            return false;
        }
    }

    /** Sends a system chat message directly to the player; returns false when no usable API exists. */
    static boolean sendSystemMessage(ServerPlayer player, Component message) {
        MessageSender sender = messageSender;
        if (sender == null && !messageUnavailable) {
            synchronized (VersionBridge.class) {
                if (messageSender == null && !messageUnavailable) {
                    messageSender = resolveMessageSender();
                    if (messageSender == null) {
                        messageUnavailable = true;
                    }
                }
                sender = messageSender;
            }
        }
        if (sender == null) {
            return false;
        }
        try {
            sender.send(player, message);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            CompatLogin.LOGGER.error("Cannot deliver a chat message on this Minecraft version", failure);
            return false;
        }
    }

    private static Function<CommandSourceStack, Boolean> resolvePermissionCheck() {
        try {
            Method legacy = legacyHasPermission();
            if (legacy != null) {
                return source -> {
                    try {
                        Object result = legacy.invoke(source, ADMIN_LEVEL);
                        return result instanceof Boolean && (Boolean) result;
                    } catch (IllegalAccessException | InvocationTargetException failure) {
                        throw new IllegalStateException("Legacy permission check failed", unwrap(failure));
                    }
                };
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // Not the legacy permission API; try the new permission API.
        }
        try {
            return modernPermissionCheck(
                "net.minecraft.server.permissions.PermissionSet",
                "ALL_PERMISSIONS",
                "net.minecraft.server.permissions.LevelBasedPermissionSet",
                "level",
                "net.minecraft.server.permissions.PermissionLevel",
                "id",
                "permissions",
                null
            );
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // Not the 26.x named runtime; try the intermediary names of the same API.
        }
        try {
            MappingResolver resolver = FabricLoader.getInstance().getMappingResolver();
            return modernPermissionCheck(
                resolver.mapClassName("intermediary", PERMISSION_SET_INTERMEDIARY),
                mapFieldName(resolver, PERMISSION_SET_INTERMEDIARY, ALL_PERMISSIONS_FIELD, "L" + PERMISSION_SET_INTERMEDIARY.replace('.', '/') + ";"),
                resolver.mapClassName("intermediary", LEVEL_BASED_PERMISSION_SET_INTERMEDIARY),
                resolver.mapMethodName("intermediary", LEVEL_BASED_PERMISSION_SET_INTERMEDIARY, LEVEL_METHOD, "()L" + PERMISSION_LEVEL_INTERMEDIARY.replace('.', '/') + ";"),
                resolver.mapClassName("intermediary", PERMISSION_LEVEL_INTERMEDIARY),
                resolver.mapMethodName("intermediary", PERMISSION_LEVEL_INTERMEDIARY, ID_METHOD, "()I"),
                null,
                mapFieldName(resolver, COMMAND_SOURCE_STACK_INTERMEDIARY, PERMISSIONS_FIELD, "L" + PERMISSION_SET_INTERMEDIARY.replace('.', '/') + ";")
            );
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // No compatible permission API at all.
        }
        CompatLogin.LOGGER.error("No compatible command permission API was found; /account migrate is disabled");
        return null;
    }

    private static Method legacyHasPermission() throws ReflectiveOperationException {
        MappingResolver resolver = FabricLoader.getInstance().getMappingResolver();
        String owner = resolver.mapClassName("intermediary", SHARED_SUGGESTION_PROVIDER_INTERMEDIARY);
        String name = resolver.mapMethodName(
            "intermediary",
            SHARED_SUGGESTION_PROVIDER_INTERMEDIARY,
            HAS_PERMISSION_METHOD,
            "(I)Z"
        );
        return Class.forName(owner, true, classLoader()).getMethod(name, int.class);
    }

    /**
     * Builds a check over the new permission API: read the source's PermissionSet (method or field),
     * allow the ALL_PERMISSIONS constant outright, otherwise require a level-based set of level >= 3.
     */
    private static Function<CommandSourceStack, Boolean> modernPermissionCheck(
        String permissionSetClassName,
        String allPermissionsFieldName,
        String levelBasedClassName,
        String levelMethodName,
        String permissionLevelClassName,
        String idMethodName,
        String permissionsMethodName,
        String permissionsFieldName
    ) throws ReflectiveOperationException {
        ClassLoader loader = classLoader();
        Class<?> permissionSetClass = Class.forName(permissionSetClassName, true, loader);
        Object allPermissions = permissionSetClass.getField(allPermissionsFieldName).get(null);
        Method permissionsMethod = permissionsMethodName == null
            ? null
            : CommandSourceStack.class.getMethod(permissionsMethodName);
        final Field permissionsField = resolvePermissionsField(permissionsFieldName);
        Method levelMethod = Class.forName(levelBasedClassName, true, loader).getMethod(levelMethodName);
        Method idMethod = Class.forName(permissionLevelClassName, true, loader).getMethod(idMethodName);
        return source -> {
            try {
                if (permissionsMethod == null && permissionsField == null) {
                    return false;
                }
                Object set = permissionsMethod != null
                    ? permissionsMethod.invoke(source)
                    : permissionsField.get(source);
                if (set == allPermissions) {
                    return true;
                }
                Object level = levelMethod.invoke(set);
                Object id = idMethod.invoke(level);
                return id instanceof Number && ((Number) id).intValue() >= ADMIN_LEVEL;
            } catch (IllegalArgumentException | IllegalAccessException | InvocationTargetException failure) {
                // Not a level-based source (e.g. an unknown PermissionSet flavour): deny.
                return false;
            }
        };
    }

    private static Field resolvePermissionsField(String permissionsFieldName) throws ReflectiveOperationException {
        if (permissionsFieldName == null) {
            return null;
        }
        try {
            return CommandSourceStack.class.getField(permissionsFieldName);
        } catch (NoSuchFieldException ignored) {
            Field field = CommandSourceStack.class.getDeclaredField(permissionsFieldName);
            field.setAccessible(true);
            return field;
        }
    }

    private static MessageSender resolveMessageSender() {
        try {
            Class<?> serverPlayer = Class.forName("net.minecraft.server.level.ServerPlayer", true, classLoader());
            Class<?> component = Class.forName("net.minecraft.network.chat.Component", true, classLoader());
            Method sendSystemMessage = serverPlayer.getMethod("sendSystemMessage", component);
            return (player, message) -> sendSystemMessage.invoke(player, message);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // Not the 26.x named runtime; try intermediary names.
        }
        try {
            MappingResolver resolver = FabricLoader.getInstance().getMappingResolver();
            Class<?> serverPlayer = Class.forName(
                resolver.mapClassName("intermediary", SERVER_PLAYER_INTERMEDIARY), true, classLoader());
            Class<?> component = Class.forName(
                resolver.mapClassName("intermediary", COMPONENT_INTERMEDIARY), true, classLoader());
            Method sendSystemMessage = serverPlayer.getMethod(
                resolver.mapMethodName(
                    "intermediary",
                    SERVER_PLAYER_INTERMEDIARY,
                    SEND_SYSTEM_MESSAGE_METHOD,
                    "(L" + COMPONENT_INTERMEDIARY.replace('.', '/') + ";)V"
                ),
                component
            );
            return (player, message) -> sendSystemMessage.invoke(player, message);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // sendSystemMessage does not exist; fall back to the 1.16 - 1.18 message API.
        }
        try {
            MappingResolver resolver = FabricLoader.getInstance().getMappingResolver();
            Class<?> playerClass = Class.forName(
                resolver.mapClassName("intermediary", PLAYER_INTERMEDIARY), true, classLoader());
            Class<?> component = Class.forName(
                resolver.mapClassName("intermediary", COMPONENT_INTERMEDIARY), true, classLoader());
            Method displayClientMessage = playerClass.getMethod(
                resolver.mapMethodName(
                    "intermediary",
                    PLAYER_INTERMEDIARY,
                    DISPLAY_CLIENT_MESSAGE_METHOD,
                    "(L" + COMPONENT_INTERMEDIARY.replace('.', '/') + ";Z)V"
                ),
                component,
                boolean.class
            );
            return (player, message) -> displayClientMessage.invoke(player, message, false);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // No usable message API on this runtime.
        }
        CompatLogin.LOGGER.error("No compatible player message API was found; migration notices are disabled");
        return null;
    }

    private static String mapFieldName(MappingResolver resolver, String owner, String field, String desc) {
        return resolver.mapFieldName("intermediary", owner, field, desc);
    }

    private static ClassLoader classLoader() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return loader == null ? VersionBridge.class.getClassLoader() : loader;
    }

    private static Throwable unwrap(ReflectiveOperationException exception) {
        if (exception instanceof InvocationTargetException) {
            Throwable cause = ((InvocationTargetException) exception).getCause();
            if (cause != null) {
                return cause;
            }
        }
        return exception;
    }

    private interface MessageSender {
        void send(ServerPlayer player, Component message) throws ReflectiveOperationException;
    }
}
