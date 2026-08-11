package cn.compatlogin.config;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

final class ConfigMigrator {
    private static final Pattern AUTHLIB_INJECTOR_MOJANG_PROXY_PATH = Pattern.compile(
        "^/https?/sessionserver\\.mojang\\.com/session/minecraft/hasJoined/?$",
        Pattern.CASE_INSENSITIVE
    );

    private ConfigMigrator() {
    }

    static List<String> repairAuthlibInjectorProxyUrls(CompatLoginConfig config) {
        List<String> migrations = new ArrayList<>();
        if (config == null || config.authentication == null || config.authentication.services == null) {
            return migrations;
        }

        for (int index = 0; index < config.authentication.services.size(); index++) {
            CompatLoginConfig.Service service = config.authentication.services.get(index);
            if (service == null || !isGeneratedMojangProxy(service)) {
                continue;
            }

            service.hasJoinedUrl = CompatLoginConfig.mojangHasJoinedUrl();
            migrations.add(
                "authentication.services[" + index + "].hasJoinedUrl: replaced authlib-injector's temporary local Mojang proxy with the direct HTTPS endpoint"
            );
        }
        return migrations;
    }

    private static boolean isGeneratedMojangProxy(CompatLoginConfig.Service service) {
        if (service.name == null || !service.name.trim().equalsIgnoreCase("Mojang") || service.hasJoinedUrl == null) {
            return false;
        }

        final URI uri;
        try {
            uri = URI.create(service.hasJoinedUrl);
        } catch (IllegalArgumentException exception) {
            return false;
        }

        return "http".equalsIgnoreCase(uri.getScheme())
            && isLoopbackHost(uri.getHost())
            && uri.getPort() >= 0
            && uri.getRawQuery() == null
            && uri.getRawFragment() == null
            && AUTHLIB_INJECTOR_MOJANG_PROXY_PATH.matcher(uri.getPath()).matches();
    }

    private static boolean isLoopbackHost(String host) {
        if (host == null) {
            return false;
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        return normalized.equals("127.0.0.1")
            || normalized.equals("localhost")
            || normalized.equals("::1")
            || normalized.equals("0:0:0:0:0:0:0:1");
    }
}
