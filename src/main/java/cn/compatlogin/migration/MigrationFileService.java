package cn.compatlogin.migration;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Performs the filesystem portion of a UUID migration as a backed-up transaction. */
public final class MigrationFileService {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int MAX_PLAYER_DATA_BYTES = 64 * 1024 * 1024;

    private MigrationFileService() {
    }

    public static Result migrate(
        Path serverRoot,
        Path worldRoot,
        PlayerIdentity source,
        PlayerIdentity target,
        Path backupBase
    ) throws IOException {
        Path normalizedServerRoot = serverRoot.toAbsolutePath().normalize();
        Path normalizedWorldRoot = worldRoot.toAbsolutePath().normalize();
        Path normalizedBackupBase = backupBase.toAbsolutePath().normalize();
        Files.createDirectories(normalizedBackupBase);

        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT).format(new Date());
        String backupName = stamp + "_" + shortUuid(source.getUuid()) + "_to_" + shortUuid(target.getUuid());
        Path backupRoot = uniqueDirectory(normalizedBackupBase, backupName);
        Files.createDirectories(backupRoot);

        List<Artifact> artifacts = artifacts(normalizedWorldRoot, source.getUuid(), target.getUuid(), backupRoot);
        Path userCache = normalizedServerRoot.resolve("usercache.json");
        Path userCacheBackup = backupRoot.resolve("server/usercache.json");
        boolean userCacheExisted = Files.exists(userCache);
        boolean changedAnyPlayerFile = false;

        try {
            for (Artifact artifact : artifacts) {
                artifact.capture();
                if (artifact.sourceExisted) {
                    changedAnyPlayerFile = true;
                    artifact.prepare(source.getUuid(), target.getUuid());
                }
            }

            if (!changedAnyPlayerFile) {
                throw new IOException("no source playerdata, advancements, or stats files were found for " + source.getUuid());
            }

            if (userCacheExisted) {
                Files.createDirectories(userCacheBackup.getParent());
                Files.copy(userCache, userCacheBackup, StandardCopyOption.REPLACE_EXISTING);
            }
            Path stagedUserCache = backupRoot.resolve("staging/usercache.json");
            prepareUserCache(userCache, stagedUserCache, source, target);

            writeManifest(backupRoot, normalizedServerRoot, normalizedWorldRoot, source, target, artifacts);

            for (Artifact artifact : artifacts) {
                artifact.commit();
            }
            replace(stagedUserCache, userCache);
            return new Result(backupRoot, countMigrated(artifacts));
        } catch (IOException | RuntimeException exception) {
            IOException rollbackFailure = rollback(artifacts, userCache, userCacheBackup, userCacheExisted);
            if (rollbackFailure != null) {
                exception.addSuppressed(rollbackFailure);
            }
            if (exception instanceof IOException) {
                throw (IOException) exception;
            }
            throw new IOException("unexpected migration failure", exception);
        } finally {
            for (Artifact artifact : artifacts) {
                artifact.cleanStaging();
            }
        }
    }

    private static List<Artifact> artifacts(Path worldRoot, UUID source, UUID target, Path backupRoot) {
        String from = source.toString();
        String to = target.toString();
        List<Artifact> result = new ArrayList<Artifact>();
        result.add(new Artifact(worldRoot.resolve("playerdata").resolve(from + ".dat"),
            worldRoot.resolve("playerdata").resolve(to + ".dat"), backupRoot, "playerdata.dat", true));
        result.add(new Artifact(worldRoot.resolve("playerdata").resolve(from + ".dat_old"),
            worldRoot.resolve("playerdata").resolve(to + ".dat_old"), backupRoot, "playerdata.dat_old", true));
        result.add(new Artifact(worldRoot.resolve("advancements").resolve(from + ".json"),
            worldRoot.resolve("advancements").resolve(to + ".json"), backupRoot, "advancements.json", false));
        result.add(new Artifact(worldRoot.resolve("stats").resolve(from + ".json"),
            worldRoot.resolve("stats").resolve(to + ".json"), backupRoot, "stats.json", false));
        return result;
    }

    private static int countMigrated(List<Artifact> artifacts) {
        int count = 0;
        for (Artifact artifact : artifacts) {
            if (artifact.sourceExisted) {
                count++;
            }
        }
        return count;
    }

    private static void prepareUserCache(
        Path userCache,
        Path staged,
        PlayerIdentity source,
        PlayerIdentity target
    ) throws IOException {
        JsonArray entries = new JsonArray();
        if (Files.exists(userCache)) {
            try (Reader reader = Files.newBufferedReader(userCache, StandardCharsets.UTF_8)) {
                JsonElement root = new JsonParser().parse(reader);
                if (!root.isJsonArray()) {
                    throw new IOException("usercache.json is not a JSON array");
                }
                entries = root.getAsJsonArray();
            } catch (RuntimeException exception) {
                throw new IOException("cannot parse usercache.json", exception);
            }
        }

        JsonArray updated = new JsonArray();
        JsonObject targetEntry = null;
        for (JsonElement element : entries) {
            if (!element.isJsonObject()) {
                updated.add(element);
                continue;
            }
            JsonObject entry = element.getAsJsonObject();
            UUID entryUuid = PlayerIdentityResolver.parseUuid(string(entry, "uuid"));
            String entryName = string(entry, "name");
            if (target.getUuid().equals(entryUuid)) {
                if (targetEntry == null) {
                    targetEntry = entry;
                }
                continue;
            }
            if (source.getUuid().equals(entryUuid)
                || (entryUuid == null && entryName != null && entryName.equalsIgnoreCase(source.getName()))) {
                continue;
            }
            updated.add(entry);
        }

        if (targetEntry == null) {
            targetEntry = new JsonObject();
            targetEntry.addProperty("expiresOn", cacheExpiry());
        }
        targetEntry.addProperty("name", target.getName());
        targetEntry.addProperty("uuid", target.getUuid().toString());
        updated.add(targetEntry);

        Files.createDirectories(staged.getParent());
        try (Writer writer = Files.newBufferedWriter(staged, StandardCharsets.UTF_8)) {
            GSON.toJson(updated, writer);
            writer.write(System.lineSeparator());
        }
    }

    private static String cacheExpiry() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT);
        format.setTimeZone(TimeZone.getDefault());
        return format.format(new Date(System.currentTimeMillis() + 30L * 24L * 60L * 60L * 1000L));
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    private static void writeManifest(
        Path backupRoot,
        Path serverRoot,
        Path worldRoot,
        PlayerIdentity source,
        PlayerIdentity target,
        List<Artifact> artifacts
    ) throws IOException {
        JsonObject manifest = new JsonObject();
        manifest.addProperty("createdAt", System.currentTimeMillis());
        manifest.addProperty("serverRoot", serverRoot.toString());
        manifest.addProperty("worldRoot", worldRoot.toString());
        manifest.addProperty("sourceName", source.getName());
        manifest.addProperty("sourceUuid", source.getUuid().toString());
        manifest.addProperty("targetName", target.getName());
        manifest.addProperty("targetUuid", target.getUuid().toString());
        JsonArray files = new JsonArray();
        for (Artifact artifact : artifacts) {
            JsonObject file = new JsonObject();
            file.addProperty("source", artifact.source.toString());
            file.addProperty("target", artifact.target.toString());
            file.addProperty("sourceExisted", artifact.sourceExisted);
            file.addProperty("targetExisted", artifact.targetExisted);
            files.add(file);
        }
        manifest.add("files", files);
        try (Writer writer = Files.newBufferedWriter(backupRoot.resolve("manifest.json"), StandardCharsets.UTF_8)) {
            GSON.toJson(manifest, writer);
            writer.write(System.lineSeparator());
        }
    }

    private static IOException rollback(
        List<Artifact> artifacts,
        Path userCache,
        Path userCacheBackup,
        boolean userCacheExisted
    ) {
        IOException failure = null;
        for (Artifact artifact : artifacts) {
            try {
                artifact.restore();
            } catch (IOException exception) {
                failure = append(failure, exception);
            }
        }
        try {
            if (userCacheExisted && Files.exists(userCacheBackup)) {
                replaceCopy(userCacheBackup, userCache);
            } else if (!userCacheExisted) {
                Files.deleteIfExists(userCache);
            }
        } catch (IOException exception) {
            failure = append(failure, exception);
        }
        return failure;
    }

    private static IOException append(IOException current, IOException next) {
        if (current == null) {
            return next;
        }
        current.addSuppressed(next);
        return current;
    }

    private static Path uniqueDirectory(Path parent, String preferredName) throws IOException {
        Path candidate = parent.resolve(preferredName);
        int suffix = 1;
        while (Files.exists(candidate)) {
            candidate = parent.resolve(preferredName + "-" + suffix++);
        }
        return candidate;
    }

    private static String shortUuid(UUID uuid) {
        return uuid.toString().substring(0, 8);
    }

    private static void replace(Path staged, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        try {
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void replaceCopy(Path source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName() + ".compatlogin-restore.tmp");
        Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
        replace(temporary, target);
    }

    private static byte[] rewritePlayerData(byte[] stored, UUID source, UUID target) throws IOException {
        boolean compressed = stored.length >= 2 && (stored[0] & 0xff) == 0x1f && (stored[1] & 0xff) == 0x8b;
        byte[] raw = compressed ? gunzip(stored) : stored;
        raw = replaceEqualLength(raw, uuidBytes(source), uuidBytes(target));
        raw = replaceEqualLength(raw,
            source.toString().getBytes(StandardCharsets.UTF_8),
            target.toString().getBytes(StandardCharsets.UTF_8));
        raw = replaceEqualLength(raw,
            source.toString().replace("-", "").getBytes(StandardCharsets.UTF_8),
            target.toString().replace("-", "").getBytes(StandardCharsets.UTF_8));
        return compressed ? gzip(raw) : raw;
    }

    private static byte[] rewriteJson(byte[] bytes, UUID source, UUID target) {
        String value = new String(bytes, StandardCharsets.UTF_8);
        value = value.replace(source.toString(), target.toString());
        value = value.replace(source.toString().replace("-", ""), target.toString().replace("-", ""));
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] uuidBytes(UUID uuid) {
        ByteBuffer buffer = ByteBuffer.allocate(16);
        buffer.putLong(uuid.getMostSignificantBits());
        buffer.putLong(uuid.getLeastSignificantBits());
        return buffer.array();
    }

    private static byte[] replaceEqualLength(byte[] input, byte[] search, byte[] replacement) {
        if (search.length != replacement.length || search.length == 0 || input.length < search.length) {
            return input;
        }
        for (int offset = 0; offset <= input.length - search.length; offset++) {
            boolean match = true;
            for (int index = 0; index < search.length; index++) {
                if (input[offset + index] != search[index]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                System.arraycopy(replacement, 0, input, offset, replacement.length);
                offset += search.length - 1;
            }
        }
        return input;
    }

    private static byte[] gunzip(byte[] compressed) throws IOException {
        try (InputStream input = new GZIPInputStream(new ByteArrayInputStream(compressed));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            copyLimited(input, output, MAX_PLAYER_DATA_BYTES);
            return output.toByteArray();
        }
    }

    private static byte[] gzip(byte[] raw) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream output = new GZIPOutputStream(bytes)) {
            output.write(raw);
        }
        return bytes.toByteArray();
    }

    private static void copyLimited(InputStream input, OutputStream output, int limit) throws IOException {
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            total += read;
            if (total > limit) {
                throw new IOException("player data exceeds the " + limit + " byte safety limit");
            }
            output.write(buffer, 0, read);
        }
    }

    public static final class Result {
        private final Path backupDirectory;
        private final int migratedFileCount;

        Result(Path backupDirectory, int migratedFileCount) {
            this.backupDirectory = backupDirectory;
            this.migratedFileCount = migratedFileCount;
        }

        public Path getBackupDirectory() {
            return backupDirectory;
        }

        public int getMigratedFileCount() {
            return migratedFileCount;
        }
    }

    private static final class Artifact {
        private final Path source;
        private final Path target;
        private final Path sourceBackup;
        private final Path targetBackup;
        private final Path staged;
        private final boolean playerData;
        private boolean sourceExisted;
        private boolean targetExisted;

        private Artifact(Path source, Path target, Path backupRoot, String label, boolean playerData) {
            this.source = source;
            this.target = target;
            this.sourceBackup = backupRoot.resolve("source").resolve(label);
            this.targetBackup = backupRoot.resolve("target").resolve(label);
            this.staged = backupRoot.resolve("staging").resolve(label);
            this.playerData = playerData;
        }

        private void capture() throws IOException {
            sourceExisted = Files.exists(source);
            targetExisted = Files.exists(target);
            if (sourceExisted) {
                Files.createDirectories(sourceBackup.getParent());
                Files.copy(source, sourceBackup, StandardCopyOption.REPLACE_EXISTING);
            }
            if (targetExisted) {
                Files.createDirectories(targetBackup.getParent());
                Files.copy(target, targetBackup, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        private void prepare(UUID sourceUuid, UUID targetUuid) throws IOException {
            byte[] bytes = Files.readAllBytes(source);
            byte[] rewritten = playerData
                ? rewritePlayerData(bytes, sourceUuid, targetUuid)
                : rewriteJson(bytes, sourceUuid, targetUuid);
            Files.createDirectories(staged.getParent());
            Files.write(staged, rewritten, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        }

        private void commit() throws IOException {
            if (!sourceExisted) {
                return;
            }
            replace(staged, target);
            Files.delete(source);
        }

        private void restore() throws IOException {
            if (sourceExisted && Files.exists(sourceBackup)) {
                replaceCopy(sourceBackup, source);
            }
            if (targetExisted && Files.exists(targetBackup)) {
                replaceCopy(targetBackup, target);
            } else if (!targetExisted && sourceExisted) {
                Files.deleteIfExists(target);
            }
        }

        private void cleanStaging() {
            try {
                Files.deleteIfExists(staged);
            } catch (IOException ignored) {
                // The retained backup makes a leftover staging file harmless and inspectable.
            }
        }
    }
}
