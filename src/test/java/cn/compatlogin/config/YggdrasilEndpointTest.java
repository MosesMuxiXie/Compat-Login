package cn.compatlogin.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class YggdrasilEndpointTest {
    @Test
    void expandsAuthlibInjectorApiRoot() {
        assertEquals(
            "https://littleskin.cn/api/yggdrasil/sessionserver/session/minecraft/hasJoined",
            YggdrasilEndpoint.resolve("https://littleskin.cn/api/yggdrasil").toString()
        );
    }

    @Test
    void expandsMojangSessionServerRoot() {
        assertEquals(
            "https://sessionserver.mojang.com/session/minecraft/hasJoined",
            YggdrasilEndpoint.resolve("https://sessionserver.mojang.com/").toString()
        );
    }

    @Test
    void preservesCompleteEndpoint() {
        String endpoint = "https://example.org/yggdrasil/sessionserver/session/minecraft/hasJoined";
        assertEquals(endpoint, YggdrasilEndpoint.resolve(endpoint).toString());
        assertEquals(endpoint, YggdrasilEndpoint.resolve(endpoint + "/").toString());
    }

    @Test
    void defaultConfigBytecodeDoesNotExposeUrlsToAuthlibInjectorTransformer() throws IOException {
        assertEquals(
            "https://sessionserver.mojang.com/session/minecraft/hasJoined",
            CompatLoginConfig.mojangHasJoinedUrl()
        );
        try (InputStream input = CompatLoginConfig.class.getResourceAsStream("CompatLoginConfig.class")) {
            String classBytes = new String(input.readAllBytes(), StandardCharsets.ISO_8859_1);
            assertFalse(classBytes.contains("https://sessionserver.mojang.com"));
            assertFalse(classBytes.contains("https://littleskin.cn"));
        }
    }
}
