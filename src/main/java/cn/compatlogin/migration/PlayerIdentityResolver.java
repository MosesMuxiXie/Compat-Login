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
import java.util.Locale;
import java.util.UUID;

final class PlayerIdentityResolver {
    private PlayerIdentityResolver() {
    }

    static PlayerIdentity resolve(MinecraftServer server, Path serverRoot, String input) throws IOException {
        UUID requestedUuid = parseUuid(input);

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Object profile = player.getGameProfile();
            UUID profileId = AuthlibProfileAdapter.readProfileId(profile);
            String profileName = AuthlibProfileAdapter.readProfileName(profile);
            if ((requestedUuid != null && requestedUuid.equals(profileId))
                || (profileName != null && profileName.equalsIgnoreCase(input))) {
                return new PlayerIdentity(profileName, profileId);
            }
        }

        Path userCache = serverRoot.resolve("usercache.json");
        if (Files.notExists(userCache)) {
            return null;
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
                String name = string(entry, "name");
                UUID uuid = parseUuid(string(entry, "uuid"));
                if (name == null || uuid == null) {
                    continue;
                }
                if ((requestedUuid != null && requestedUuid.equals(uuid))
                    || name.toLowerCase(Locale.ROOT).equals(input.toLowerCase(Locale.ROOT))) {
                    return new PlayerIdentity(name, uuid);
                }
            }
        } catch (RuntimeException exception) {
            throw new IOException("cannot parse usercache.json", exception);
        }
        return null;
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
