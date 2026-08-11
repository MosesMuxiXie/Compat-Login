package cn.compatlogin;

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

final class AuthlibInjectorCompatibility {
    private AuthlibInjectorCompatibility() {
    }

    static boolean detectAndLog() {
        Optional<String> argument = findAuthlibInjectorArgument(
            ManagementFactory.getRuntimeMXBean().getInputArguments()
        );
        if (!argument.isPresent()) {
            return false;
        }

        CompatLogin.LOGGER.info(
            "Detected server-side authlib-injector; compatibility mode is enabled and configured identity providers will be queried directly"
        );
        return true;
    }

    static Optional<String> findAuthlibInjectorArgument(List<String> inputArguments) {
        return inputArguments.stream()
            .filter(AuthlibInjectorCompatibility::isAuthlibInjectorArgument)
            .findFirst();
    }

    private static boolean isAuthlibInjectorArgument(String argument) {
        String normalized = argument.toLowerCase(Locale.ROOT);
        return normalized.startsWith("-javaagent:") && normalized.contains("authlib-injector");
    }
}
