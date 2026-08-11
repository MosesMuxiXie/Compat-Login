package cn.compatlogin.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigManagerTest {
    @Test
    void failureMessageKeepsWarningsVisibleInFabricAndMcdrStackTraces() {
        String message = ConfigManager.buildFailureMessage(
            Path.of("server", "config", ConfigManager.FILE_NAME),
            List.of(
                "[WARNING] config/compat_login.json -> schemaVersion: must be 1",
                "[WARNING] config/compat_login.json -> authentication.services: must contain at least one service"
            )
        );

        assertTrue(message.contains("2 issue(s)"));
        assertTrue(message.contains("[WARNING] config/compat_login.json -> schemaVersion: must be 1"));
        assertTrue(message.contains("[WARNING] config/compat_login.json -> authentication.services: must contain at least one service"));
        assertTrue(message.contains("no authentication fallback was enabled"));
    }

    @Test
    void invalidFileExceptionContainsTheExactFieldWarning(@TempDir Path directory) throws IOException {
        Path configPath = directory.resolve(ConfigManager.FILE_NAME);
        Files.writeString(configPath, """
            {
              "schemaVersion": 2,
              "authentication": {
                "connectTimeoutSeconds": 5,
                "requestTimeoutSeconds": 8,
                "maxResponseBytes": 1048576,
                "allowInsecureHttp": false,
                "services": [
                  {
                    "name": "LittleSkin",
                    "enabled": true,
                    "hasJoinedUrl": "https://littleskin.cn/api/yggdrasil"
                  }
                ]
              }
            }
            """, StandardCharsets.UTF_8);

        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> ConfigManager.load(configPath)
        );

        assertTrue(exception.getMessage().contains("1 issue(s)"));
        assertTrue(exception.getMessage().contains(
            "[WARNING] config/compat_login.json -> schemaVersion: must be 1, but was 2"
        ));
    }

    @Test
    void migratesAndBacksUpAnAuthlibInjectorGeneratedProxy(@TempDir Path directory) throws IOException {
        Path configPath = directory.resolve(ConfigManager.FILE_NAME);
        Files.writeString(configPath, """
            {
              "schemaVersion": 1,
              "authentication": {
                "connectTimeoutSeconds": 5,
                "requestTimeoutSeconds": 8,
                "maxResponseBytes": 1048576,
                "allowInsecureHttp": false,
                "services": [
                  {
                    "name": "Mojang",
                    "enabled": true,
                    "hasJoinedUrl": "http://127.0.0.1:50378/https/sessionserver.mojang.com/session/minecraft/hasJoined"
                  }
                ]
              }
            }
            """, StandardCharsets.UTF_8);

        CompatLoginConfig config = ConfigManager.load(configPath);

        assertEquals(CompatLoginConfig.mojangHasJoinedUrl(), config.authentication.services.get(0).hasJoinedUrl);
        assertTrue(Files.exists(directory.resolve(ConfigManager.FILE_NAME + ".authlib-injector.bak")));
        assertTrue(Files.readString(configPath).contains(CompatLoginConfig.mojangHasJoinedUrl()));
    }
}
