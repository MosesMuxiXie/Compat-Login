package cn.compatlogin.auth;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class AuthlibProfileAdapterTest {
    private static final UUID PLAYER_ID = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

    @Test
    void createsTheLegacyGameProfileUsedByMinecraft116() throws Exception {
        AuthenticatedProfile source = new AuthenticatedProfile(
            PLAYER_ID,
            "Notch",
            List.of(new AuthenticatedProfile.ProfileProperty("textures", "value", "signature"))
        );

        Object gameProfile = AuthlibProfileAdapter.createGameProfile(source);

        assertEquals("com.mojang.authlib.GameProfile", gameProfile.getClass().getName());
        assertEquals(source.getId(), invoke(gameProfile, "getId", "id"));
        assertEquals("Notch", invoke(gameProfile, "getName", "name"));
        assertEquals(source.getId(), AuthlibProfileAdapter.readProfileId(gameProfile));
        assertEquals("Notch", AuthlibProfileAdapter.readProfileName(gameProfile));
        Object properties = invoke(gameProfile, "getProperties", "properties");
        assertTrue((Boolean) properties.getClass().getMethod("containsKey", Object.class).invoke(properties, "textures"));
    }

    @Test
    void readsTheJavaBeanGameProfileShapeUsedByAuthlib2To6() {
        assertEquals(PLAYER_ID, AuthlibProfileAdapter.readProfileId(new LegacyProfileShape(PLAYER_ID, "Moss_X")));
        assertEquals("Moss_X", AuthlibProfileAdapter.readProfileName(new LegacyProfileShape(PLAYER_ID, "Moss_X")));
    }

    /**
     * Regression test for the Minecraft 1.21.9+ login crash: {@code PlayerList.canPlayerLogin} receives a
     * {@code NameAndId} record that has only {@code id()}/{@code name()}. The adapter used to probe
     * {@code getId()} first, so the mixin threw {@code IllegalStateException} and the connection died with
     * "Internal server error".
     */
    @Test
    void readsTheRecordShapeUsedByNameAndIdSinceMinecraft1219() {
        assertEquals(PLAYER_ID, AuthlibProfileAdapter.readProfileId(new NameAndIdShape(PLAYER_ID, "Moss_X")));
        assertEquals("Moss_X", AuthlibProfileAdapter.readProfileName(new NameAndIdShape(PLAYER_ID, "Moss_X")));
    }

    /**
     * Runs the adapter against the real {@code NameAndId} class of the Minecraft line under test, not just
     * a fixture. The modern line carries {@code com.mojang.authlib.yggdrasil.response.NameAndId} on its dev
     * classpath; the 1.21.11 line compiles against the legacy 1.16.5 jar, so its
     * {@code net.minecraft.server.players.NameAndId} exists but its static {@code Codec} cannot link in the
     * test JVM - that line is covered by the fixture test above and by the bytecode check instead.
     */
    @Test
    void readsTheRealNameAndIdOfThisMinecraftLine() throws Exception {
        Class<?> type = findType("com.mojang.authlib.yggdrasil.response.NameAndId");
        assumeTrue(type != null, "this line's dev classpath carries no linkable NameAndId class");

        Object identity = type.getConstructor(UUID.class, String.class).newInstance(PLAYER_ID, "Moss_X");

        assertEquals(PLAYER_ID, AuthlibProfileAdapter.readProfileId(identity));
        assertEquals("Moss_X", AuthlibProfileAdapter.readProfileName(identity));
    }

    @Test
    void reportsTheUnsupportedShapeAsAnAuthlibProfileFailure() {
        IllegalStateException idFailure = assertThrows(IllegalStateException.class, () ->
            AuthlibProfileAdapter.readProfileId(new UnsupportedShape())
        );
        assertEquals("Cannot read the player UUID from an authlib GameProfile", idFailure.getMessage());
        assertNotNull(idFailure.getCause());
        assertEquals(NoSuchMethodException.class, idFailure.getCause().getClass());

        IllegalStateException nameFailure = assertThrows(IllegalStateException.class, () ->
            AuthlibProfileAdapter.readProfileName(new UnsupportedShape())
        );
        assertEquals("Cannot read the player name from an authlib GameProfile", nameFailure.getMessage());
        assertEquals(NoSuchMethodException.class, nameFailure.getCause().getClass());
    }

    @Test
    void recreatesAuthlibsCheckedUnavailableException() {
        Throwable thrown = assertThrows(Throwable.class, () ->
            AuthlibProfileAdapter.throwAuthenticationUnavailable(
                new AuthenticationServiceUnavailableException("provider unavailable")
            )
        );

        assertEquals(
            "com.mojang.authlib.exceptions.AuthenticationUnavailableException",
            thrown.getClass().getName()
        );
        assertNotNull(thrown.getMessage());
    }

    private static Object invoke(Object target, String oldName, String newName) throws Exception {
        Method method;
        try {
            method = target.getClass().getMethod(oldName);
        } catch (NoSuchMethodException ignored) {
            method = target.getClass().getMethod(newName);
        }
        return method.invoke(target);
    }

    /**
     * Returns the first class that the current dev classpath actually carries, or {@code null}. A class that
     * exists but cannot be linked (missing game libraries) counts as absent, so the caller skips instead of
     * failing on an environment gap.
     */
    private static Class<?> findType(String... names) {
        for (String name : names) {
            try {
                return Class.forName(name);
            } catch (ClassNotFoundException | LinkageError ignored) {
                // Try the next candidate: each Minecraft line ships a different one.
            }
        }
        return null;
    }

    /** Mirrors authlib 2.x-6.x {@code GameProfile}: JavaBean accessors. */
    private static final class LegacyProfileShape {
        private final UUID id;
        private final String name;

        private LegacyProfileShape(UUID id, String name) {
            this.id = id;
            this.name = name;
        }

        public UUID getId() {
            return id;
        }

        public String getName() {
            return name;
        }
    }

    /** Mirrors authlib 7+ {@code GameProfile} and 1.21.9+ {@code NameAndId}: record accessors only. */
    private static final class NameAndIdShape {
        private final UUID id;
        private final String name;

        private NameAndIdShape(UUID id, String name) {
            this.id = id;
            this.name = name;
        }

        public UUID id() {
            return id;
        }

        public String name() {
            return name;
        }
    }

    /** Mirrors an identity object whose accessors this bridge does not know. */
    private static final class UnsupportedShape {
    }
}
