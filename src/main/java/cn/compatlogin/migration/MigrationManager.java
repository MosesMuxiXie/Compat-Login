package cn.compatlogin.migration;

import cn.compatlogin.CompatLogin;
import cn.compatlogin.auth.AuthlibProfileAdapter;
import cn.compatlogin.mixin.PlayerListAccessor;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

public final class MigrationManager {
    private static final long CODE_LIFETIME_MILLIS = 15L * 60L * 1000L;
    private static final long CONFIRMATION_LIFETIME_MILLIS = 5L * 60L * 1000L;
    private static final long DISCONNECT_TIMEOUT_MILLIS = 30L * 1000L;
    private static final long TEMPORARY_BAN_MILLIS = 5L * 60L * 1000L;
    private static final long TICK_INTERVAL_MILLIS = 250L;
    private static final String BAN_REASON = "player data migration taking place, wait for 5 minutes";
    private static final String CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final Pattern COMMAND_SAFE_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final List<MigrationSession> SESSIONS = new ArrayList<MigrationSession>();
    private static Path storePath;
    private static boolean loaded;
    private static long lastTickMillis;
    private static long lastLoadErrorLogMillis;

    private MigrationManager() {
    }

    public static boolean canBegin(CommandSourceStack source) {
        Entity entity = source.getEntity();
        if (!(entity instanceof ServerPlayer)) {
            String name = source.getTextName();
            return "Server".equalsIgnoreCase(name) || "Rcon".equalsIgnoreCase(name);
        }

        Object profile = ((ServerPlayer) entity).getGameProfile();
        UUID profileId = AuthlibProfileAdapter.readProfileId(profile);
        Path opsFile = FabricLoader.getInstance().getGameDir().resolve("ops.json");
        if (Files.notExists(opsFile)) {
            return false;
        }
        try (Reader reader = Files.newBufferedReader(opsFile, StandardCharsets.UTF_8)) {
            JsonElement root = new JsonParser().parse(reader);
            if (!root.isJsonArray()) {
                return false;
            }
            for (JsonElement element : root.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject entry = element.getAsJsonObject();
                UUID uuid = PlayerIdentityResolver.parseUuid(string(entry, "uuid"));
                if (profileId.equals(uuid) && integer(entry, "level", 0) >= 3) {
                    return true;
                }
            }
        } catch (IOException | RuntimeException exception) {
            CompatLogin.LOGGER.warn("Cannot read ops.json while checking /account migrate permission", exception);
        }
        return false;
    }

    public static synchronized int begin(CommandSourceStack commandSource, String from, String to) {
        try {
            ensureLoaded();
            if (!canBegin(commandSource)) {
                ServerCommandBridge.reply(commandSource, "You need administrator permission level 3 or higher to begin a migration.");
                return 0;
            }

            MinecraftServer server = commandSource.getServer();
            Path serverRoot = FabricLoader.getInstance().getGameDir();
            PlayerIdentity source = PlayerIdentityResolver.resolve(server, serverRoot, from);
            PlayerIdentity target = PlayerIdentityResolver.resolve(server, serverRoot, to);
            if (source == null) {
                ServerCommandBridge.reply(commandSource,
                    "Cannot resolve source account '" + from + "'. Have that account join once, or supply its UUID.");
                return 0;
            }
            if (target == null) {
                ServerCommandBridge.reply(commandSource,
                    "Cannot resolve target account '" + to + "'. Have that account join once, or supply its UUID.");
                return 0;
            }
            if (source.getUuid().equals(target.getUuid())) {
                ServerCommandBridge.reply(commandSource, "Source and target already use the same UUID.");
                return 0;
            }
            if (!commandSafe(source.getName()) || !commandSafe(target.getName())) {
                ServerCommandBridge.reply(commandSource,
                    "Both cached player names must use Minecraft's A-Z, 0-9 and underscore name format.");
                return 0;
            }
            if (involvesActiveSession(source.getUuid()) || involvesActiveSession(target.getUuid())) {
                ServerCommandBridge.reply(commandSource, "One of those UUIDs already has an active migration request.");
                return 0;
            }

            long now = System.currentTimeMillis();
            String code = newCode();
            String requestedBy = requesterName(commandSource);
            MigrationSession session = new MigrationSession(
                code,
                source,
                target,
                requestedBy,
                now,
                now + CODE_LIFETIME_MILLIS
            );
            SESSIONS.add(session);
            persist();

            ServerCommandBridge.reply(commandSource,
                "Migration " + source + " -> " + target + " is ready. One-time code: " + code
                    + " (valid for 15 minutes). The target UUID must join and confirm it.");
            CompatLogin.LOGGER.info(
                "Migration requested by {}: {} ({}) -> {} ({})",
                requestedBy,
                source.getName(),
                source.getUuid(),
                target.getName(),
                target.getUuid()
            );
            return 1;
        } catch (IOException | RuntimeException exception) {
            CompatLogin.LOGGER.error("Cannot create player data migration request", exception);
            ServerCommandBridge.reply(commandSource, "Could not create the migration request; see the server log.");
            return 0;
        }
    }

    public static synchronized int confirm(CommandSourceStack commandSource, String suppliedCode) {
        try {
            ensureLoaded();
            ServerPlayer player;
            try {
                player = ServerCommandBridge.requirePlayer(commandSource);
            } catch (CommandSyntaxException exception) {
                ServerCommandBridge.reply(commandSource, "Only the target player account can confirm a migration.");
                return 0;
            }

            MigrationSession session = findByCode(suppliedCode);
            long now = System.currentTimeMillis();
            if (session == null || !MigrationSession.AWAITING_CONFIRMATION.equals(session.state)) {
                ServerCommandBridge.reply(commandSource, "That migration code is invalid or has already been used.");
                return 0;
            }
            if (now > session.codeExpiresAtMillis
                || (session.confirmationExpiresAtMillis > 0 && now > session.confirmationExpiresAtMillis)) {
                SESSIONS.remove(session);
                persist();
                ServerCommandBridge.reply(commandSource, "That migration request has expired.");
                return 0;
            }
            if (!session.targetUuid().equals(player.getUUID())) {
                ServerCommandBridge.reply(commandSource, "This code belongs to a different target UUID.");
                CompatLogin.LOGGER.warn(
                    "Rejected migration confirmation code {} from unexpected UUID {}",
                    session.code,
                    player.getUUID()
                );
                return 0;
            }
            if (findOnline(commandSource.getServer(), session.sourceUuid()) != null) {
                ServerCommandBridge.reply(commandSource,
                    "The source account is still online. Log it out before confirming the migration.");
                return 0;
            }

            if (session.promptedAtMillis == 0) {
                session.promptedAtMillis = now;
                session.confirmationExpiresAtMillis = Math.min(
                    session.codeExpiresAtMillis,
                    now + CONFIRMATION_LIFETIME_MILLIS
                );
            }
            session.state = MigrationSession.WAITING_FOR_DISCONNECT;
            session.disconnectDeadlineMillis = now + DISCONNECT_TIMEOUT_MILLIS;
            session.unbanAtMillis = now + TEMPORARY_BAN_MILLIS;
            persist();

            ServerCommandBridge.reply(commandSource,
                "Migration confirmed. You will be disconnected while the server replaces the UUID data.");
            boolean banned = ServerCommandBridge.execute(
                commandSource.getServer(),
                "ban " + session.targetName + " " + BAN_REASON
            );
            if (!banned) {
                SESSIONS.remove(session);
                persist();
                ServerCommandBridge.reply(commandSource, "The temporary ban failed, so no player data was changed.");
                return 0;
            }
            CompatLogin.LOGGER.info(
                "Migration code {} confirmed by target {} ({}); waiting for disconnect",
                session.code,
                session.targetName,
                session.targetUuid
            );
            return 1;
        } catch (IOException | RuntimeException exception) {
            CompatLogin.LOGGER.error("Cannot confirm player data migration", exception);
            ServerCommandBridge.reply(commandSource, "Could not confirm the migration; see the server log.");
            return 0;
        }
    }

    public static synchronized void tick(MinecraftServer server) {
        long now = System.currentTimeMillis();
        if (now - lastTickMillis < TICK_INTERVAL_MILLIS) {
            return;
        }
        lastTickMillis = now;

        try {
            ensureLoaded();
        } catch (IOException | RuntimeException exception) {
            if (now - lastLoadErrorLogMillis > 60_000L) {
                lastLoadErrorLogMillis = now;
                CompatLogin.LOGGER.error("Cannot load pending player migrations", exception);
            }
            return;
        }

        boolean dirty = false;
        Iterator<MigrationSession> iterator = SESSIONS.iterator();
        while (iterator.hasNext()) {
            MigrationSession session = iterator.next();
            try {
                if (MigrationSession.AWAITING_CONFIRMATION.equals(session.state)) {
                    if (now > session.codeExpiresAtMillis
                        || (session.confirmationExpiresAtMillis > 0 && now > session.confirmationExpiresAtMillis)) {
                        ServerPlayer target = findOnline(server, session.targetUuid());
                        if (target != null) {
                            ServerCommandBridge.tell(server, session.targetName, "Migration request expired; no data was changed.");
                        }
                        CompatLogin.LOGGER.info("Migration code {} expired", session.code);
                        iterator.remove();
                        dirty = true;
                        continue;
                    }
                    ServerPlayer target = findOnline(server, session.targetUuid());
                    if (target != null && session.promptedAtMillis == 0) {
                        session.promptedAtMillis = now;
                        session.confirmationExpiresAtMillis = Math.min(
                            session.codeExpiresAtMillis,
                            now + CONFIRMATION_LIFETIME_MILLIS
                        );
                        ServerCommandBridge.tell(
                            server,
                            session.targetName,
                            "confirm migrate data from " + session.sourceName + " to this account?"
                        );
                        ServerCommandBridge.tell(
                            server,
                            session.targetName,
                            "If yes, enter /account migrate confirm " + session.code
                                + " within 5 minutes. Otherwise ignore this message."
                        );
                        dirty = true;
                    }
                    continue;
                }

                if (MigrationSession.WAITING_FOR_DISCONNECT.equals(session.state)
                    || MigrationSession.MIGRATING.equals(session.state)) {
                    if (findOnline(server, session.targetUuid()) != null) {
                        if (now > session.disconnectDeadlineMillis) {
                            ServerCommandBridge.execute(server, "pardon " + session.targetName);
                            notifyRequester(server, session,
                                "Migration failed because the target did not disconnect; no data was changed.");
                            iterator.remove();
                            dirty = true;
                        }
                        continue;
                    }
                    if (findOnline(server, session.sourceUuid()) != null) {
                        ServerCommandBridge.execute(server, "pardon " + session.targetName);
                        notifyRequester(server, session,
                            "Migration cancelled because the source UUID came online; no data was changed.");
                        iterator.remove();
                        dirty = true;
                        continue;
                    }

                    session.state = MigrationSession.MIGRATING;
                    persist();
                    MigrationFileService.Result result = MigrationFileService.migrate(
                        FabricLoader.getInstance().getGameDir(),
                        server.getWorldPath(LevelResource.ROOT),
                        session.sourceIdentity(),
                        session.targetIdentity(),
                        FabricLoader.getInstance().getConfigDir().resolve("compat_login/migration-backups")
                    );
                    clearPlayerCaches(server, session.sourceUuid(), session.targetUuid());
                    session.backupPath = result.getBackupDirectory().toString();
                    session.state = MigrationSession.COMPLETED;
                    dirty = true;
                    notifyRequester(server, session,
                        "Migration completed: " + result.getMigratedFileCount() + " player file(s) replaced. Backup: "
                            + result.getBackupDirectory().toAbsolutePath());
                    CompatLogin.LOGGER.info(
                        "Migration completed: {} ({}) -> {} ({}); backup at {}",
                        session.sourceName,
                        session.sourceUuid,
                        session.targetName,
                        session.targetUuid,
                        result.getBackupDirectory().toAbsolutePath()
                    );
                    continue;
                }

                if (MigrationSession.COMPLETED.equals(session.state) && now >= session.unbanAtMillis) {
                    ServerCommandBridge.execute(server, "pardon " + session.targetName);
                    notifyRequester(server, session,
                        "The 5-minute migration ban for " + session.targetName + " has been removed.");
                    iterator.remove();
                    dirty = true;
                }
            } catch (IOException | RuntimeException exception) {
                CompatLogin.LOGGER.error(
                    "Player migration {} ({}) -> {} ({}) failed; attempting to remove the temporary ban",
                    session.sourceName,
                    session.sourceUuid,
                    session.targetName,
                    session.targetUuid,
                    exception
                );
                ServerCommandBridge.execute(server, "pardon " + session.targetName);
                notifyRequester(server, session, "Migration failed and was rolled back; see the server log.");
                iterator.remove();
                dirty = true;
            }
        }

        if (dirty) {
            try {
                persist();
            } catch (IOException exception) {
                CompatLogin.LOGGER.error("Cannot persist player migration state", exception);
            }
        }
    }

    private static void clearPlayerCaches(MinecraftServer server, UUID source, UUID target) {
        PlayerListAccessor accessor = (PlayerListAccessor) server.getPlayerList();
        accessor.compatLogin$getStats().remove(source);
        accessor.compatLogin$getStats().remove(target);
        accessor.compatLogin$getAdvancements().remove(source);
        accessor.compatLogin$getAdvancements().remove(target);
    }

    private static ServerPlayer findOnline(MinecraftServer server, UUID uuid) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (uuid.equals(player.getUUID())) {
                return player;
            }
        }
        return null;
    }

    private static boolean involvesActiveSession(UUID uuid) {
        for (MigrationSession session : SESSIONS) {
            if (uuid.equals(session.sourceUuid()) || uuid.equals(session.targetUuid())) {
                return true;
            }
        }
        return false;
    }

    private static MigrationSession findByCode(String code) {
        String normalized = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        for (MigrationSession session : SESSIONS) {
            if (session.code.equals(normalized)) {
                return session;
            }
        }
        return null;
    }

    private static String newCode() {
        String code;
        do {
            StringBuilder builder = new StringBuilder(8);
            for (int index = 0; index < 8; index++) {
                builder.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
            }
            code = builder.toString();
        } while (findByCode(code) != null);
        return code;
    }

    private static String requesterName(CommandSourceStack source) {
        Entity entity = source.getEntity();
        if (entity instanceof ServerPlayer) {
            return AuthlibProfileAdapter.readProfileName(((ServerPlayer) entity).getGameProfile());
        }
        return source.getTextName();
    }

    private static void notifyRequester(MinecraftServer server, MigrationSession session, String message) {
        CompatLogin.LOGGER.info("[account migrate] {}", message);
        if (commandSafe(session.requestedBy) && findOnlineByName(server, session.requestedBy) != null) {
            ServerCommandBridge.tell(server, session.requestedBy, message);
        }
    }

    private static ServerPlayer findOnlineByName(MinecraftServer server, String name) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (AuthlibProfileAdapter.readProfileName(player.getGameProfile()).equalsIgnoreCase(name)) {
                return player;
            }
        }
        return null;
    }

    private static boolean commandSafe(String name) {
        return name != null && COMMAND_SAFE_NAME.matcher(name).matches();
    }

    private static void ensureLoaded() throws IOException {
        if (loaded) {
            return;
        }
        storePath = FabricLoader.getInstance().getConfigDir().resolve("compat_login-migrations.json");
        SESSIONS.clear();
        if (Files.exists(storePath)) {
            try (Reader reader = Files.newBufferedReader(storePath, StandardCharsets.UTF_8)) {
                StoreFile store = GSON.fromJson(reader, StoreFile.class);
                if (store == null || store.sessions == null) {
                    throw new IOException("migration state file has no sessions array");
                }
                for (MigrationSession session : store.sessions) {
                    if (valid(session)) {
                        SESSIONS.add(session);
                    } else {
                        CompatLogin.LOGGER.warn("Ignored an invalid entry in {}", storePath.toAbsolutePath());
                    }
                }
            } catch (RuntimeException exception) {
                throw new IOException("cannot parse " + storePath.toAbsolutePath(), exception);
            }
        }
        loaded = true;
    }

    private static boolean valid(MigrationSession session) {
        if (session == null || session.code == null || session.sourceName == null || session.targetName == null
            || session.sourceUuid == null || session.targetUuid == null || session.state == null) {
            return false;
        }
        try {
            session.sourceUuid();
            session.targetUuid();
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static void persist() throws IOException {
        if (storePath == null) {
            storePath = FabricLoader.getInstance().getConfigDir().resolve("compat_login-migrations.json");
        }
        Files.createDirectories(storePath.getParent());
        Path temporary = storePath.resolveSibling(storePath.getFileName() + ".tmp");
        StoreFile store = new StoreFile();
        store.sessions.addAll(SESSIONS);
        try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
            GSON.toJson(store, writer);
            writer.write(System.lineSeparator());
        }
        try {
            Files.move(temporary, storePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            Files.move(temporary, storePath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    private static int integer(JsonObject object, String key, int fallback) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? fallback : value.getAsInt();
    }

    private static final class StoreFile {
        int schemaVersion = 1;
        List<MigrationSession> sessions = new ArrayList<MigrationSession>();
    }
}
