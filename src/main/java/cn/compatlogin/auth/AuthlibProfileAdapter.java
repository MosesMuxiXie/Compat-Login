package cn.compatlogin.auth;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.UUID;

/** Bridges authlib 2.x GameProfile and authlib 6+/7+ ProfileResult at runtime. */
public final class AuthlibProfileAdapter {
    private static final String GAME_PROFILE_CLASS = "com.mojang.authlib.GameProfile";
    private static final String PROPERTY_CLASS = "com.mojang.authlib.properties.Property";
    private static final String PROPERTY_MAP_CLASS = "com.mojang.authlib.properties.PropertyMap";
    private static final String MULTIMAP_CLASS = "com.google.common.collect.Multimap";
    private static final String LINKED_HASH_MULTIMAP_CLASS = "com.google.common.collect.LinkedHashMultimap";
    private static final String PROFILE_RESULT_CLASS = "com.mojang.authlib.yggdrasil.ProfileResult";
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
            Class<?> profileResultClass = load(PROFILE_RESULT_CLASS);
            return profileResultClass.getConstructor(load(GAME_PROFILE_CLASS)).newInstance(gameProfile);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot create an authlib ProfileResult for this Minecraft version", unwrap(exception));
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

    private static Object invokeAccessor(Object target, String oldName, String newName)
        throws ReflectiveOperationException {
        try {
            return target.getClass().getMethod(oldName).invoke(target);
        } catch (NoSuchMethodException ignored) {
            return target.getClass().getMethod(newName).invoke(target);
        }
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
