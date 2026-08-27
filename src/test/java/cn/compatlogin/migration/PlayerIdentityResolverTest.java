package cn.compatlogin.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerIdentityResolverTest {
    private static final String MOJANG_UUID = "069a79f4-44e9-4726-a5be-fca90e38aaf5";
    private static final String OFFLINE_UUID = "11111111-2222-3333-4444-555555555555";

    @TempDir
    Path serverRoot;

    @Test
    void reportsEveryUuidThatSharesOneName() throws IOException {
        Path userCache = writeUserCache("""
            [
              {"name": "Notch", "uuid": "%s"},
              {"name": "notch", "uuid": "%s"}
            ]
            """.formatted(MOJANG_UUID, OFFLINE_UUID));

        List<PlayerIdentity> matches = PlayerIdentityResolver.fromUserCache(userCache, "Notch", null);

        assertEquals(2, matches.size());
        assertTrue(matches.stream().anyMatch(identity -> identity.getUuid().equals(UUID.fromString(MOJANG_UUID))));
        assertTrue(matches.stream().anyMatch(identity -> identity.getUuid().equals(UUID.fromString(OFFLINE_UUID))));
    }

    @Test
    void ignoresDuplicateEntriesForTheSameUuid() throws IOException {
        Path userCache = writeUserCache("""
            [
              {"name": "Notch", "uuid": "%s"},
              {"name": "Notch", "uuid": "%s"}
            ]
            """.formatted(MOJANG_UUID, MOJANG_UUID.replace("-", "")));

        List<PlayerIdentity> matches = PlayerIdentityResolver.fromUserCache(userCache, "Notch", null);

        assertEquals(1, matches.size());
        assertEquals("Notch", matches.get(0).getName());
    }

    @Test
    void matchesByUuidWithoutConsideringNames() throws IOException {
        Path userCache = writeUserCache("""
            [
              {"name": "Notch", "uuid": "%s"},
              {"name": "Herobrine", "uuid": "%s"}
            ]
            """.formatted(MOJANG_UUID, OFFLINE_UUID));

        List<PlayerIdentity> matches =
            PlayerIdentityResolver.fromUserCache(userCache, null, UUID.fromString(OFFLINE_UUID));

        assertEquals(1, matches.size());
        assertEquals("Herobrine", matches.get(0).getName());
    }

    @Test
    void skipsEntriesWithoutAUsableNameOrUuid() throws IOException {
        Path userCache = writeUserCache("""
            [
              "not-an-object",
              {"name": "Notch"},
              {"uuid": "%s"},
              {"name": "Notch", "uuid": "not-a-uuid"}
            ]
            """.formatted(MOJANG_UUID));

        assertTrue(PlayerIdentityResolver.fromUserCache(userCache, "Notch", null).isEmpty());
    }

    @Test
    void treatsAMissingCacheAsNoMatch() throws IOException {
        assertTrue(PlayerIdentityResolver.fromUserCache(serverRoot.resolve("usercache.json"), "Notch", null).isEmpty());
    }

    @Test
    void acceptsUuidsWithAndWithoutDashes() {
        assertEquals(UUID.fromString(MOJANG_UUID), PlayerIdentityResolver.parseUuid(MOJANG_UUID));
        assertEquals(UUID.fromString(MOJANG_UUID), PlayerIdentityResolver.parseUuid(MOJANG_UUID.replace("-", "")));
        assertNull(PlayerIdentityResolver.parseUuid("Notch"));
    }

    private Path writeUserCache(String json) throws IOException {
        Path userCache = serverRoot.resolve("usercache.json");
        Files.write(userCache, json.getBytes(StandardCharsets.UTF_8));
        return userCache;
    }
}
