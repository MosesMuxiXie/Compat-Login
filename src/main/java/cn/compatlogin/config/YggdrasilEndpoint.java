package cn.compatlogin.config;

import java.net.URI;

/** Resolves either a complete hasJoined URL or an authlib-injector API root. */
public final class YggdrasilEndpoint {
    public static final String HAS_JOINED_PATH_SUFFIX = "/session/minecraft/hasJoined";
    private static final String SESSION_SERVER_PATH = "/sessionserver";
    private static final String MOJANG_SESSION_HOST = "sessionserver.mojang.com";

    private YggdrasilEndpoint() {
    }

    public static URI resolve(String configuredUrl) {
        return resolve(URI.create(configuredUrl));
    }

    public static URI resolve(URI configuredUri) {
        String path = configuredUri.getPath();
        String pathWithoutTrailingSlashes = stripTrailingSlashes(path == null ? "" : path);

        if (pathWithoutTrailingSlashes.endsWith(HAS_JOINED_PATH_SUFFIX)) {
            return URI.create(stripTrailingSlashes(configuredUri.toString()));
        }

        String suffix;
        if (pathWithoutTrailingSlashes.endsWith(SESSION_SERVER_PATH)
            || (pathWithoutTrailingSlashes.isEmpty() && isMojangSessionHost(configuredUri.getHost()))) {
            suffix = HAS_JOINED_PATH_SUFFIX;
        } else {
            suffix = SESSION_SERVER_PATH + HAS_JOINED_PATH_SUFFIX;
        }

        String base = stripTrailingSlashes(configuredUri.toString());
        return URI.create(base + suffix);
    }

    private static boolean isMojangSessionHost(String host) {
        return host != null && host.equalsIgnoreCase(MOJANG_SESSION_HOST);
    }

    private static String stripTrailingSlashes(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }
}
