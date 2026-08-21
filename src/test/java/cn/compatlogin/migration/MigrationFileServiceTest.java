package cn.compatlogin.migration;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationFileServiceTest {
    private static final UUID SOURCE_UUID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID TARGET_UUID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    @TempDir
    Path temporaryDirectory;

    @Test
    void replacesUuidDataAndRetainsARecoveryBackup() throws Exception {
        Path serverRoot = temporaryDirectory.resolve("server");
        Path worldRoot = serverRoot.resolve("world");
        Path backupBase = serverRoot.resolve("config/compat_login/migration-backups");
        Files.createDirectories(worldRoot.resolve("playerdata"));
        Files.createDirectories(worldRoot.resolve("advancements"));
        Files.createDirectories(worldRoot.resolve("stats"));

        byte[] sourceNbt = playerBytes(SOURCE_UUID, "source=" + SOURCE_UUID);
        Path sourcePlayerData = worldRoot.resolve("playerdata/" + SOURCE_UUID + ".dat");
        Path targetPlayerData = worldRoot.resolve("playerdata/" + TARGET_UUID + ".dat");
        Files.write(sourcePlayerData, gzip(sourceNbt));
        Files.write(targetPlayerData, gzip("old target".getBytes(StandardCharsets.UTF_8)));
        Files.write(
            worldRoot.resolve("advancements/" + SOURCE_UUID + ".json"),
            ("{\"owner\":\"" + SOURCE_UUID + "\",\"done\":true}").getBytes(StandardCharsets.UTF_8)
        );
        Files.write(
            worldRoot.resolve("stats/" + SOURCE_UUID + ".json"),
            "{\"stats\":{\"minecraft:custom\":{\"minecraft:jump\":7}}}".getBytes(StandardCharsets.UTF_8)
        );
        Files.createDirectories(serverRoot);
        Files.write(
            serverRoot.resolve("usercache.json"),
            ("["
                + "{\"name\":\"OldName\",\"uuid\":\"" + SOURCE_UUID + "\",\"expiresOn\":\"x\"},"
                + "{\"name\":\"NewName\",\"uuid\":\"" + TARGET_UUID + "\",\"expiresOn\":\"y\"},"
                + "{\"name\":\"SomeoneElse\",\"uuid\":\"00000000-0000-0000-0000-000000000001\"}"
                + "]").getBytes(StandardCharsets.UTF_8)
        );

        MigrationFileService.Result result = MigrationFileService.migrate(
            serverRoot,
            worldRoot,
            new PlayerIdentity("OldName", SOURCE_UUID),
            new PlayerIdentity("NewName", TARGET_UUID),
            backupBase
        );

        assertEquals(3, result.getMigratedFileCount());
        assertFalse(Files.exists(sourcePlayerData));
        assertFalse(Files.exists(worldRoot.resolve("advancements/" + SOURCE_UUID + ".json")));
        assertFalse(Files.exists(worldRoot.resolve("stats/" + SOURCE_UUID + ".json")));

        byte[] migratedNbt = gunzip(Files.readAllBytes(targetPlayerData));
        assertTrue(contains(migratedNbt, uuidBytes(TARGET_UUID)));
        assertFalse(contains(migratedNbt, uuidBytes(SOURCE_UUID)));
        assertTrue(new String(migratedNbt, StandardCharsets.ISO_8859_1).contains(TARGET_UUID.toString()));

        String advancement = new String(
            Files.readAllBytes(worldRoot.resolve("advancements/" + TARGET_UUID + ".json")),
            StandardCharsets.UTF_8
        );
        assertTrue(advancement.contains(TARGET_UUID.toString()));
        assertFalse(advancement.contains(SOURCE_UUID.toString()));

        JsonArray cache = readArray(serverRoot.resolve("usercache.json"));
        assertEquals(2, cache.size());
        assertFalse(hasUuid(cache, SOURCE_UUID));
        assertTrue(hasUuid(cache, TARGET_UUID));

        assertTrue(Files.exists(result.getBackupDirectory().resolve("source/playerdata.dat")));
        assertTrue(Files.exists(result.getBackupDirectory().resolve("target/playerdata.dat")));
        assertTrue(Files.exists(result.getBackupDirectory().resolve("server/usercache.json")));
        assertTrue(Files.exists(result.getBackupDirectory().resolve("manifest.json")));
        assertArrayEquals(
            "old target".getBytes(StandardCharsets.UTF_8),
            gunzip(Files.readAllBytes(result.getBackupDirectory().resolve("target/playerdata.dat")))
        );
    }

    @Test
    void refusesToEraseTargetWhenNoSourceDataExists() throws Exception {
        Path serverRoot = temporaryDirectory.resolve("server-empty");
        Path worldRoot = serverRoot.resolve("world");
        Path target = worldRoot.resolve("playerdata/" + TARGET_UUID + ".dat");
        Files.createDirectories(target.getParent());
        Files.write(target, "keep me".getBytes(StandardCharsets.UTF_8));

        assertThrows(IOException.class, () -> MigrationFileService.migrate(
            serverRoot,
            worldRoot,
            new PlayerIdentity("OldName", SOURCE_UUID),
            new PlayerIdentity("NewName", TARGET_UUID),
            serverRoot.resolve("backups")
        ));

        assertEquals("keep me", new String(Files.readAllBytes(target), StandardCharsets.UTF_8));
    }

    private static JsonArray readArray(Path path) throws IOException {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return new JsonParser().parse(reader).getAsJsonArray();
        }
    }

    private static boolean hasUuid(JsonArray entries, UUID uuid) {
        for (JsonElement element : entries) {
            JsonObject object = element.getAsJsonObject();
            if (uuid.toString().equals(object.get("uuid").getAsString())) {
                return true;
            }
        }
        return false;
    }

    private static byte[] playerBytes(UUID uuid, String text) {
        byte[] prefix = "nbt-prefix".getBytes(StandardCharsets.UTF_8);
        byte[] binaryUuid = uuidBytes(uuid);
        byte[] suffix = text.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(prefix.length + binaryUuid.length + suffix.length);
        return buffer.put(prefix).put(binaryUuid).put(suffix).array();
    }

    private static byte[] uuidBytes(UUID uuid) {
        return ByteBuffer.allocate(16)
            .putLong(uuid.getMostSignificantBits())
            .putLong(uuid.getLeastSignificantBits())
            .array();
    }

    private static byte[] gzip(byte[] value) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream output = new GZIPOutputStream(bytes)) {
            output.write(value);
        }
        return bytes.toByteArray();
    }

    private static byte[] gunzip(byte[] value) throws IOException {
        try (InputStream input = new GZIPInputStream(new ByteArrayInputStream(value));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        for (int offset = 0; offset <= haystack.length - needle.length; offset++) {
            boolean match = true;
            for (int index = 0; index < needle.length; index++) {
                if (haystack[offset + index] != needle[index]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return true;
            }
        }
        return false;
    }
}
