package cn.compatlogin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerPropertiesGuardTest {
    @Test
    void acceptsOnlineModeAfterReadingTheRealPropertiesFile(@TempDir Path directory) throws IOException {
        Path properties = directory.resolve("server.properties");
        Files.writeString(properties, "online-mode=true\nenforce-secure-profile=false\n", StandardCharsets.ISO_8859_1);

        assertDoesNotThrow(() -> ServerPropertiesGuard.validate(properties, false));
    }

    @Test
    void rejectsOfflineModeWithAnActionableWarning(@TempDir Path directory) throws IOException {
        Path properties = directory.resolve("server.properties");
        Files.writeString(properties, "online-mode=false\n", StandardCharsets.ISO_8859_1);

        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> ServerPropertiesGuard.validate(properties, false)
        );

        assertTrue(exception.getMessage().contains("online-mode=true"));
        assertTrue(exception.getMessage().contains("[WARNING] server.properties -> online-mode"));
    }

    @Test
    void allowsTheFirstRunBeforeMinecraftCreatesServerProperties(@TempDir Path directory) {
        assertDoesNotThrow(() -> ServerPropertiesGuard.validate(directory.resolve("server.properties"), false));
    }
}
