package cn.compatlogin.config;

import java.util.ArrayList;
import java.util.List;

public final class CompatLoginConfig {
    public int schemaVersion = 1;
    public Authentication authentication = new Authentication();

    public static CompatLoginConfig defaults() {
        CompatLoginConfig config = new CompatLoginConfig();
        config.authentication.services.add(new Service(
            "Mojang",
            true,
            mojangHasJoinedUrl()
        ));
        config.authentication.services.add(new Service(
            "LittleSkin",
            true,
            littleSkinHasJoinedUrl()
        ));
        return config;
    }

    public static String mojangHasJoinedUrl() {
        return String.join(
            "",
            "https",
            "://",
            "sessionserver.",
            "mojang.com",
            "/session/minecraft/",
            "hasJoined"
        );
    }

    public static String littleSkinHasJoinedUrl() {
        return String.join(
            "",
            "https",
            "://",
            "littleskin.cn",
            "/api/yggdrasil/sessionserver/session/minecraft/",
            "hasJoined"
        );
    }

    public static final class Authentication {
        public int connectTimeoutSeconds = 5;
        public int requestTimeoutSeconds = 8;
        public int maxResponseBytes = 1_048_576;
        public boolean allowInsecureHttp = false;
        public List<Service> services = new ArrayList<>();
    }

    public static final class Service {
        public String name;
        public Boolean enabled;
        public String hasJoinedUrl;

        public Service() {
        }

        public Service(String name, boolean enabled, String hasJoinedUrl) {
            this.name = name;
            this.enabled = enabled;
            this.hasJoinedUrl = hasJoinedUrl;
        }
    }
}
