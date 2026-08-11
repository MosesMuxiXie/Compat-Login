package cn.compatlogin.config;

import cn.compatlogin.CompatLogin;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ConfigManager {
    public static final String FILE_NAME = "compat_login.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().serializeNulls().create();

    private ConfigManager() {
    }

    public static CompatLoginConfig load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
        return load(path);
    }

    static CompatLoginConfig load(Path path) {
        if (Files.notExists(path)) {
            writeDefault(path);
            CompatLogin.LOGGER.info("Created default configuration at {}", path.toAbsolutePath());
        }

        final CompatLoginConfig config;
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            config = GSON.fromJson(reader, CompatLoginConfig.class);
        } catch (JsonParseException exception) {
            fail(path, Collections.singletonList("$: invalid JSON: " + readableMessage(exception)));
            throw new AssertionError("unreachable");
        } catch (IOException exception) {
            fail(path, Collections.singletonList("$: cannot read file: " + readableMessage(exception)));
            throw new AssertionError("unreachable");
        }

        List<String> migrations = ConfigMigrator.repairAuthlibInjectorProxyUrls(config);
        if (!migrations.isEmpty()) {
            for (String migration : migrations) {
                CompatLogin.LOGGER.warn("{}", warningLine(migration));
            }
            persistMigration(path, config);
        }

        List<String> issues = ConfigValidator.validate(config);
        if (!issues.isEmpty()) {
            fail(path, issues);
        }
        return config;
    }

    private static void writeDefault(Path path) {
        try {
            writeConfig(path, CompatLoginConfig.defaults());
        } catch (IOException exception) {
            fail(path, Collections.singletonList("$: cannot create default configuration: " + readableMessage(exception)));
        }
    }

    private static void persistMigration(Path path, CompatLoginConfig config) {
        Path backup = path.resolveSibling(FILE_NAME + ".authlib-injector.bak");
        try {
            if (Files.notExists(backup)) {
                Files.copy(path, backup);
            }
            writeConfig(path, config);
            CompatLogin.LOGGER.info(
                "Updated authlib-injector compatibility settings in {}; original configuration is backed up at {}",
                path.toAbsolutePath(),
                backup.toAbsolutePath()
            );
        } catch (IOException exception) {
            CompatLogin.LOGGER.warn(
                "[WARNING] config/{} -> repaired authlib-injector proxy URL in memory but could not persist it: {}",
                FILE_NAME,
                readableMessage(exception)
            );
        }
    }

    private static void writeConfig(Path path, CompatLoginConfig config) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
            writer.write(GSON.toJson(config));
            writer.write(System.lineSeparator());
        }
        try {
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void fail(Path path, List<String> issues) {
        List<String> warningLines = new ArrayList<String>();
        for (String issue : issues) {
            warningLines.add(warningLine(issue));
        }
        for (String warningLine : warningLines) {
            CompatLogin.LOGGER.warn(warningLine);
        }
        CompatLogin.LOGGER.warn("[WARNING] Fix {} and restart the server; the invalid file was not overwritten", path.toAbsolutePath());
        throw new IllegalStateException(buildFailureMessage(path, warningLines));
    }

    static String buildFailureMessage(Path path, List<String> warningLines) {
        String separator = System.lineSeparator();
        return "Compat Login configuration is invalid (" + path.toAbsolutePath().normalize() + ", "
            + warningLines.size() + " issue(s))" + separator
            + String.join(separator, warningLines) + separator
            + "[WARNING] Fix config/" + FILE_NAME + " and restart the server; no authentication fallback was enabled";
    }

    private static String warningLine(String issue) {
        return "[WARNING] config/" + FILE_NAME + " -> " + issue.replace('\r', ' ').replace('\n', ' ');
    }

    private static String readableMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.trim().isEmpty() ? exception.getClass().getSimpleName() : message;
    }
}
