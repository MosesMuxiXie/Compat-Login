package cn.compatlogin;

import cn.compatlogin.auth.MultiAuthService;
import cn.compatlogin.config.CompatLoginConfig;
import cn.compatlogin.config.ConfigManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CompatLogin implements ModInitializer {
    public static final String MOD_ID = "compat_login";
    public static final Logger LOGGER = LoggerFactory.getLogger("Compat Login");

    private static volatile MultiAuthService authenticator;

    @Override
    public void onInitialize() {
        boolean authlibInjectorActive = AuthlibInjectorCompatibility.detectAndLog();
        CompatLoginConfig config = ConfigManager.load();
        authenticator = new MultiAuthService(config.authentication);

        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            if (!server.usesAuthentication()) {
                LOGGER.warn("[WARNING] server.properties -> online-mode: must be true; otherwise every login bypasses authentication");
                throw new IllegalStateException("Compat Login requires online-mode=true");
            }

            if (!authlibInjectorActive && server.enforceSecureProfile()) {
                LOGGER.warn("[WARNING] server.properties -> enforce-secure-profile: LittleSkin clients without a trusted chat profile key may be rejected; use false unless every configured identity provider supports trusted profile keys");
            }
        });

        if (authenticator.providerCount() > 1) {
            LOGGER.warn("[WARNING] authentication.services: multiple identity sources can own the same player name; configure permissions, bans and allowlists by UUID rather than name");
        }
        LOGGER.info("Compat Login initialized with {} enabled authentication service(s)", authenticator.providerCount());
    }

    public static MultiAuthService authenticator() {
        return authenticator;
    }
}
