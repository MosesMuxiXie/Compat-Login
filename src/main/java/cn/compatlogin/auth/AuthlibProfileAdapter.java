package cn.compatlogin.auth;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Bridges every identity object Minecraft hands to this mod across the supported range:
 *
 * <ul>
 *   <li>authlib 2.x-6.x {@code com.mojang.authlib.GameProfile} - {@code getId()}/{@code getName()};</li>
 *   <li>authlib 7+ {@code GameProfile}, now a record - {@code id()}/{@code name()};</li>
 *   <li>Minecraft 1.21.9-1.21.11 {@code net.minecraft.server.players.NameAndId} -
 *       {@code id()}/{@code name()} in Mojang mappings, but {@code comp_4422()}/{@code comp_4423()}
 *       on intermediary-mapped Fabric production servers ({@code net.minecraft.class_11560});</li>
 *   <li>authlib {@code NameAndId} in the {@code yggdrasil.response} or (authlib 10)
 *       {@code services.response} package - {@code id()}/{@code name()};</li>
 *   <li>authlib 6+ {@code ProfileResult}, moved from {@code yggdrasil} to {@code services} in authlib 10.</li>
 * </ul>
 *
 * The public API keeps its original names, but every reader accepts all accessor shapes, so callers
 * never need to know which identity type they hold.
 */
public final class AuthlibProfileAdapter {
    private static final String GAME_PROFILE_CLASS = "com.mojang.authlib.GameProfile";
    private static final String PROPERTY_CLASS = "com.mojang.authlib.properties.Property";
    private static final String PROPERTY_MAP_CLASS = "com.mojang.authlib.properties.PropertyMap";
    private static final String MULTIMAP_CLASS = "com.google.common.collect.Multimap";
    private static final String LINKED_HASH_MULTIMAP_CLASS = "com.google.common.collect.LinkedHashMultimap";
    private static final String PROFILE_RESULT_CLASS = "com.mojang.authlib.yggdrasil.ProfileResult";
    private static final String SERVICES_PROFILE_RESULT_CLASS = "com.mojang.authlib.services.ProfileResult";
    private static final String AUTHENTICATION_UNAVAILABLE_CLASS =
        "com.mojang.authlib.exceptions.AuthenticationUnavailableException";

    private AuthlibProfileAdapter() {
    }

    public static Object createGameProfile(AuthenticatedProfile profile) {
        try {
            Class<?> gameProfileClass = load(GAME_PROFILE_CLASS);
            Class<?> propertyMapClass = load(PROPERTY_MAP_CLASS);

            try {
                Constructor<?> constructor = gameProfileClass.getConstructor(
                    UUID.class,
                    String.class,
                    propertyMapClass
                );
                return constructor.newInstance(
                    profile.getId(),
                    profile.getName(),
                    createImmutablePropertyMap(profile)
                );
            } catch (NoSuchMethodException ignored) {
                Object gameProfile = gameProfileClass
                    .getConstructor(UUID.class, String.class)
                    .newInstance(profile.getId(), profile.getName());
                Object propertyMap = invokeAccessor(gameProfile, "getProperties", "properties");
                addProperties(propertyMap, profile);
                return gameProfile;
            }
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot create an authlib GameProfile for this Minecraft version", unwrap(exception));
        }
    }

    public static Object createProfileResult(Object gameProfile) {
        try {
            Class<?> profileResultClass;
            try {
                profileResultClass = load(PROFILE_RESULT_CLASS);
            } catch (ClassNotFoundException ignored) {
                // authlib 10 (Minecraft 26.3) moved the session-service implementation and its DTOs.
                profileResultClass = load(SERVICES_PROFILE_RESULT_CLASS);
            }
            return profileResultClass.getConstructor(load(GAME_PROFILE_CLASS)).newInstance(gameProfile);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot create an authlib ProfileResult for this Minecraft version", unwrap(exception));
        }
    }

    /**
     * Reads the player name from any supported identity object. The record accessor is probed first:
     * {@code NameAndId} (Minecraft 1.21.9+) only has {@code name()}, while a {@code GameProfile} of any
     * authlib version exposes at least one of the two. Fabric production servers remap the Minecraft
     * record accessor to {@code comp_4423()}; reflection strings are not remapped by Loom.
     */
    public static String readProfileName(Object gameProfile) {
        try {
            return (String) invokeAccessor(gameProfile, "name", "getName", "comp_4423");
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot read the player name from an authlib GameProfile", unwrap(exception));
        }
    }

    /**
     * Reads the player UUID from any supported identity object. The record accessor is probed first:
     * {@code NameAndId} (Minecraft 1.21.9+) and authlib 7+ {@code GameProfile} only have {@code id()},
     * while authlib 2.x-6.x {@code GameProfile} only has {@code getId()}. Fabric production servers
     * remap the Minecraft record accessor to {@code comp_4422()}; reflection strings are not remapped.
     */
    public static UUID readProfileId(Object gameProfile) {
        try {
            return (UUID) invokeAccessor(gameProfile, "id", "getId", "comp_4422");
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot read the player UUID from an authlib GameProfile", unwrap(exception));
        }
    }

    public static void throwAuthenticationUnavailable(AuthenticationServiceUnavailableException cause) {
        Throwable authlibException;
        try {
            Class<?> type = load(AUTHENTICATION_UNAVAILABLE_CLASS);
            authlibException = constructAuthenticationUnavailable(type, cause);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot create authlib AuthenticationUnavailableException", unwrap(exception));
        }
        AuthlibProfileAdapter.<RuntimeException>throwUnchecked(authlibException);
    }

    private static Object createImmutablePropertyMap(AuthenticatedProfile profile)
        throws ReflectiveOperationException {
        Class<?> multimapClass = load(MULTIMAP_CLASS);
        Object multimap = load(LINKED_HASH_MULTIMAP_CLASS).getMethod("create").invoke(null);
        addProperties(multimap, profile);
        return load(PROPERTY_MAP_CLASS).getConstructor(multimapClass).newInstance(multimap);
    }

    private static void addProperties(Object propertyMap, AuthenticatedProfile profile)
        throws ReflectiveOperationException {
        Method put = propertyMap.getClass().getMethod("put", Object.class, Object.class);
        Class<?> propertyClass = load(PROPERTY_CLASS);
        for (AuthenticatedProfile.ProfileProperty property : profile.getProperties()) {
            Object authlibProperty;
            if (property.getSignature() == null) {
                authlibProperty = propertyClass
                    .getConstructor(String.class, String.class)
                    .newInstance(property.getName(), property.getValue());
            } else {
                authlibProperty = propertyClass
                    .getConstructor(String.class, String.class, String.class)
                    .newInstance(property.getName(), property.getValue(), property.getSignature());
            }
            put.invoke(propertyMap, property.getName(), authlibProperty);
        }
    }

    /**
     * Invokes the first accessor name that the runtime class actually declares. The names are tried in
     * order and the last {@code NoSuchMethodException} is rethrown, so a failure message still names the
     * candidate that was probed last (that is how {@code NameAndId} surfaced as
     * {@code NoSuchMethodException: net.minecraft.class_11560.id()}).
     */
    private static Object invokeAccessor(Object target, String... candidateNames)
        throws ReflectiveOperationException {
        NoSuchMethodException lastFailure = null;
        for (String candidateName : candidateNames) {
            try {
                return target.getClass().getMethod(candidateName).invoke(target);
            } catch (NoSuchMethodException missing) {
                lastFailure = missing;
            }
        }
        throw lastFailure == null
            ? new NoSuchMethodException("no accessor candidate supplied")
            : lastFailure;
    }

    private static Throwable constructAuthenticationUnavailable(
        Class<?> type,
        AuthenticationServiceUnavailableException cause
    ) throws ReflectiveOperationException {
        try {
            return (Throwable) type
                .getConstructor(String.class, Throwable.class)
                .newInstance(cause.getMessage(), cause);
        } catch (NoSuchMethodException ignored) {
            try {
                return (Throwable) type.getConstructor(String.class).newInstance(cause.getMessage());
            } catch (NoSuchMethodException ignoredAgain) {
                return (Throwable) type.getConstructor(Throwable.class).newInstance(cause);
            }
        }
    }

    private static Class<?> load(String name) throws ClassNotFoundException {
        ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        return Class.forName(name, true, contextLoader == null ? AuthlibProfileAdapter.class.getClassLoader() : contextLoader);
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

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void throwUnchecked(Throwable throwable) throws T {
        throw (T) throwable;
    }
}
