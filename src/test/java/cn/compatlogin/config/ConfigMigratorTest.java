package cn.compatlogin.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigMigratorTest {
    @Test
    void repairsTheTemporaryMojangProxyWrittenByAuthlibInjector() {
        CompatLoginConfig config = CompatLoginConfig.defaults();
        config.authentication.services.get(0).hasJoinedUrl =
            "http://127.0.0.1:50378/https/sessionserver.mojang.com/session/minecraft/hasJoined";

        List<String> migrations = ConfigMigrator.repairAuthlibInjectorProxyUrls(config);

        assertEquals(1, migrations.size());
        assertEquals(CompatLoginConfig.mojangHasJoinedUrl(), config.authentication.services.get(0).hasJoinedUrl);
        assertTrue(ConfigValidator.validate(config).isEmpty());
    }

    @Test
    void preservesUserConfiguredLocalProviders() {
        CompatLoginConfig config = CompatLoginConfig.defaults();
        config.authentication.allowInsecureHttp = true;
        config.authentication.services.set(0, new CompatLoginConfig.Service(
            "Trusted local provider",
            true,
            "http://127.0.0.1:50378/https/sessionserver.mojang.com/session/minecraft/hasJoined"
        ));

        assertTrue(ConfigMigrator.repairAuthlibInjectorProxyUrls(config).isEmpty());
        assertTrue(config.authentication.services.get(0).hasJoinedUrl.startsWith("http://127.0.0.1:"));
    }
}
