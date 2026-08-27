package cn.compatlogin.migration;

import cn.compatlogin.auth.AuthlibProfileAdapter;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class PlayerIdentityResolver {
    private PlayerIdentityResolver() {
    }

    static PlayerIdentity resolve(MinecraftServer server, Path serverRoot, String input)
        throws IOException, AmbiguousPlayerNameException {
        Path userCache = serverRoot.resolve("usercache.json");
        UUID requestedUuid = parseUuid(input);
        if (requestedUuid != null) {
            PlayerIdentity online = onlineByUuid(server, requestedUuid);
            if (online != null) {
                return online;
            }
            List<PlayerIdentity> cached = fromUserCache(userCache, null, requestedUuid);
            return cached.isEmpty() ? null : cached.get(0);
        }

        List<PlayerIdentity> matches = onlineByName(server, input);
        for (PlayerIdentity cached : fromUserCache(userCache, input, null)) {
            addUnique(matches, cached);
        }
        if (matches.isEmpty()) {
            return null;
        }
        if (matches.size() > 1) {
            throw new AmbiguousPlayerNameException(input, matches);
        }
        return matches.get(0);
    }

    private static PlayerIdentity onlineByUuid(MinecraftServer server, UUID uuid) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Object profile = player.getGameProfile();
            if (uuid.equals(AuthlibProfileAdapter.readProfileId(profile))) {
                return new PlayerIdentity(AuthlibProfileAdapter.readProfileName(profile), uuid);
            }
        }
        return null;
    }

    private static List<PlayerIdentity> onlineByName(MinecraftServer server, String name) {
        List<PlayerIdentity> matches = new ArrayList<PlayerIdentity>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Object profile = player.getGameProfile();
            String profileName = AuthlibProfileAdapter.readProfileName(profile);
            UUID profileId = AuthlibProfileAdapter.readProfileId(profile);
            if (profileName != null && profileId != null && profileName.equalsIgnoreCase(name)) {
                addUnique(matches, new PlayerIdentity(profileName, profileId));
            }
        }
        return matches;
    }

    /** Returns every cached identity matching the requested name or UUID, at most one entry per UUID. */
    static List<PlayerIdentity> fromUserCache(Path userCache, String name, UUID uuid) throws IOException {
        List<PlayerIdentity> matches = new ArrayList<PlayerIdentity>();
        if (Files.notExists(userCache)) {
            return matches;
        }

        try (Reader reader = Files.newBufferedReader(userCache, StandardCharsets.UTF_8)) {
            JsonElement root = new JsonParser().parse(reader);
            if (!root.isJsonArray()) {
                throw new IOException("usercache.json is not a JSON array");
            }
            JsonArray entries = root.getAsJsonArray();
            for (JsonElement element : entries) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject entry = element.getAsJsonObject();
                String entryName = string(entry, "name");
                UUID entryUuid = parseUuid(string(entry, "uuid"));
                if (entryName == null || entryUuid == null) {
                    continue;
                }
                if ((uuid != null && uuid.equals(entryUuid))
                    || (name != null && entryName.equalsIgnoreCase(name))) {
                    addUnique(matches, new PlayerIdentity(entryName, entryUuid));
                }
            }
        } catch (RuntimeException exception) {
            throw new IOException("cannot parse usercache.json", exception);
        }
        return matches;
    }

    private static void addUnique(List<PlayerIdentity> matches, PlayerIdentity candidate) {
        for (PlayerIdentity known : matches) {
            if (known.getUuid().equals(candidate.getUuid())) {
                return;
            }
        }
        matches.add(candidate);
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    static UUID parseUuid(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() == 32) {
            normalized = normalized.substring(0, 8) + "-"
                + normalized.substring(8, 12) + "-"
                + normalized.substring(12, 16) + "-"
                + normalized.substring(16, 20) + "-"
                + normalized.substring(20);
        }
        try {
            return UUID.fromString(normalized);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
