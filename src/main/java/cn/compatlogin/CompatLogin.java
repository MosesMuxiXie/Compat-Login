package cn.compatlogin;

import cn.compatlogin.auth.MultiAuthService;
import cn.compatlogin.config.CompatLoginConfig;
import cn.compatlogin.config.ConfigManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class CompatLogin implements ModInitializer {
    public static final String MOD_ID = "compat_login";
    public static final Logger LOGGER = LogManager.getLogger("Compat Login");

    private static volatile MultiAuthService authenticator;

    @Override
    public void onInitialize() {
        boolean authlibInjectorActive = AuthlibInjectorCompatibility.detectAndLog();
        CompatLoginConfig config = ConfigManager.load();
        ServerPropertiesGuard.validate(
            FabricLoader.getInstance().getGameDir().resolve("server.properties"),
            authlibInjectorActive
        );
        authenticator = new MultiAuthService(config.authentication);

        if (authenticator.providerCount() > 1) {
            LOGGER.warn("[WARNING] authentication.services: multiple identity sources can own the same player name; configure permissions, bans and allowlists by UUID rather than name");
        }
        LOGGER.info("Compat Login initialized with {} enabled authentication service(s)", authenticator.providerCount());
    }

    public static MultiAuthService authenticator() {
        return authenticator;
    }
}
