package cn.compatlogin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

final class ServerPropertiesGuard {
    private ServerPropertiesGuard() {
    }

    static void validate(Path serverProperties, boolean authlibInjectorActive) {
        if (Files.notExists(serverProperties)) {
            CompatLogin.LOGGER.info(
                "server.properties does not exist yet; Minecraft will create it with online-mode=true by default"
            );
            return;
        }

        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(serverProperties)) {
            properties.load(input);
        } catch (IOException exception) {
            String warning = "[WARNING] server.properties -> cannot read file: " + readableMessage(exception);
            CompatLogin.LOGGER.warn(warning);
            throw new IllegalStateException(warning + System.lineSeparator()
                + "[WARNING] Fix " + serverProperties.toAbsolutePath() + " and restart the server", exception);
        }

        String onlineMode = properties.getProperty("online-mode", "true").trim();
        if (!"true".equalsIgnoreCase(onlineMode)) {
            String warning = "[WARNING] server.properties -> online-mode: must be true; otherwise every login bypasses authentication";
            CompatLogin.LOGGER.warn(warning);
            throw new IllegalStateException("Compat Login requires online-mode=true ("
                + serverProperties.toAbsolutePath().normalize() + ")" + System.lineSeparator() + warning);
        }

        String secureProfile = properties.getProperty("enforce-secure-profile");
        if (!authlibInjectorActive && secureProfile != null && "true".equalsIgnoreCase(secureProfile.trim())) {
            CompatLogin.LOGGER.warn(
                "[WARNING] server.properties -> enforce-secure-profile: third-party clients without a trusted chat profile key may be rejected; use false unless every configured identity provider supports trusted profile keys"
            );
        }
    }

    private static String readableMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.trim().isEmpty() ? exception.getClass().getSimpleName() : message;
    }
}
