package cn.compatlogin.auth;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthlibProfileAdapterTest {
    @Test
    void createsTheLegacyGameProfileUsedByMinecraft116() throws Exception {
        AuthenticatedProfile source = new AuthenticatedProfile(
            UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5"),
            "Notch",
            List.of(new AuthenticatedProfile.ProfileProperty("textures", "value", "signature"))
        );

        Object gameProfile = AuthlibProfileAdapter.createGameProfile(source);

        assertEquals("com.mojang.authlib.GameProfile", gameProfile.getClass().getName());
        assertEquals(source.getId(), invoke(gameProfile, "getId", "id"));
        assertEquals("Notch", invoke(gameProfile, "getName", "name"));
        assertEquals("Notch", AuthlibProfileAdapter.readProfileName(gameProfile));
        Object properties = invoke(gameProfile, "getProperties", "properties");
        assertTrue((Boolean) properties.getClass().getMethod("containsKey", Object.class).invoke(properties, "textures"));
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
}
