package cn.compatlogin.config;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class ConfigValidator {
    private ConfigValidator() {
    }

    public static List<String> validate(CompatLoginConfig config) {
        List<String> issues = new ArrayList<>();
        if (config == null) {
            issues.add("$: configuration must be a JSON object");
            return issues;
        }

        if (config.schemaVersion != 1) {
            issues.add("schemaVersion: must be 1, but was " + config.schemaVersion);
        }

        CompatLoginConfig.Authentication authentication = config.authentication;
        if (authentication == null) {
            issues.add("authentication: is required");
            return issues;
        }

        validateRange(issues, "authentication.connectTimeoutSeconds", authentication.connectTimeoutSeconds, 1, 30);
        validateRange(issues, "authentication.requestTimeoutSeconds", authentication.requestTimeoutSeconds, 1, 60);
        validateRange(issues, "authentication.maxResponseBytes", authentication.maxResponseBytes, 1_024, 4_194_304);

        if (authentication.services == null || authentication.services.isEmpty()) {
            issues.add("authentication.services: must contain at least one service");
            return issues;
        }

        int enabledCount = 0;
        Set<String> names = new HashSet<>();
        Set<String> endpoints = new HashSet<>();
        for (int index = 0; index < authentication.services.size(); index++) {
            CompatLoginConfig.Service service = authentication.services.get(index);
            String path = "authentication.services[" + index + "]";
            if (service == null) {
                issues.add(path + ": must be an object");
                continue;
            }

            if (isBlank(service.name)) {
                issues.add(path + ".name: must not be blank");
            } else {
                if (service.name.length() > 64) {
                    issues.add(path + ".name: must contain at most 64 characters");
                }
                if (!names.add(service.name.toLowerCase(Locale.ROOT))) {
                    issues.add(path + ".name: duplicate service name '" + service.name + "'");
                }
            }

            if (service.enabled == null) {
                issues.add(path + ".enabled: is required and must be true or false");
            } else if (service.enabled) {
                enabledCount++;
            }

            validateEndpoint(issues, endpoints, path, service.hasJoinedUrl, authentication.allowInsecureHttp);
        }

        if (enabledCount == 0) {
            issues.add("authentication.services: at least one service must have enabled=true");
        }
        return issues;
    }

    private static void validateRange(List<String> issues, String path, int value, int minimum, int maximum) {
        if (value < minimum || value > maximum) {
            issues.add(path + ": must be between " + minimum + " and " + maximum + ", but was " + value);
        }
    }

    private static void validateEndpoint(
        List<String> issues,
        Set<String> endpoints,
        String servicePath,
        String value,
        boolean allowInsecureHttp
    ) {
        String path = servicePath + ".hasJoinedUrl";
        if (isBlank(value)) {
            issues.add(path + ": must not be blank");
            return;
        }

        final URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException exception) {
            issues.add(path + ": invalid URI: " + exception.getMessage());
            return;
        }

        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("https") && !scheme.equalsIgnoreCase("http"))) {
            issues.add(path + ": scheme must be https" + (allowInsecureHttp ? " or http" : ""));
        } else if (scheme.equalsIgnoreCase("http") && !allowInsecureHttp) {
            String message = path + ": http is disabled; use https or set authentication.allowInsecureHttp=true for a trusted local service";
            if (isLoopbackHost(uri.getHost())) {
                message += "; unrecognized local authlib-injector proxy URLs should be replaced with the provider's direct HTTPS API address";
            }
            issues.add(message);
        }
        if (!uri.isAbsolute() || uri.getHost() == null) {
            issues.add(path + ": must be an absolute HTTP(S) URL with a host");
        }
        if (uri.getUserInfo() != null) {
            issues.add(path + ": user information is not allowed in the URL");
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            issues.add(path + ": query strings and fragments are not allowed; the mod adds username, serverId and ip parameters");
        }
        URI resolvedUri = YggdrasilEndpoint.resolve(uri);
        String normalized = resolvedUri.normalize().toString().toLowerCase(Locale.ROOT);
        if (!endpoints.add(normalized)) {
            issues.add(path + ": duplicate hasJoined endpoint");
        }
    }

    private static boolean isLoopbackHost(String host) {
        if (host == null) {
            return false;
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        return normalized.equals("localhost")
            || normalized.equals("127.0.0.1")
            || normalized.equals("::1")
            || normalized.equals("0:0:0:0:0:0:0:1");
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
