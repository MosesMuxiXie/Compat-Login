package cn.compatlogin.auth;

import cn.compatlogin.CompatLogin;
import cn.compatlogin.config.CompatLoginConfig;
import cn.compatlogin.config.YggdrasilEndpoint;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

public final class MultiAuthService {
    private static final long WARNING_INTERVAL_NANOS = 30L * 1_000_000_000L;
    private static final String USER_AGENT = "Compat-Login/" + modVersion();

    private final int connectTimeoutMillis;
    private final int requestTimeoutMillis;
    private final int maxResponseBytes;
    private final List<Provider> providers;

    public MultiAuthService(CompatLoginConfig.Authentication config) {
        this.connectTimeoutMillis = config.connectTimeoutSeconds * 1_000;
        this.requestTimeoutMillis = config.requestTimeoutSeconds * 1_000;
        this.maxResponseBytes = config.maxResponseBytes;

        List<Provider> enabledProviders = new ArrayList<Provider>();
        for (int index = 0; index < config.services.size(); index++) {
            CompatLoginConfig.Service service = config.services.get(index);
            if (Boolean.TRUE.equals(service.enabled)) {
                enabledProviders.add(new Provider(
                    index,
                    service.name,
                    YggdrasilEndpoint.resolve(service.hasJoinedUrl)
                ));
            }
        }
        this.providers = Collections.unmodifiableList(enabledProviders);
    }

    public int providerCount() {
        return providers.size();
    }

    public AuthenticatedProfile hasJoinedServer(String username, String serverId, InetAddress address)
        throws AuthenticationServiceUnavailableException {
        List<String> failures = new ArrayList<String>();
        for (Provider provider : providers) {
            if (Thread.currentThread().isInterrupted()) {
                throw new AuthenticationServiceUnavailableException("Authentication request was interrupted");
            }

            try {
                AuthenticatedProfile result = query(provider, username, serverId, address);
                if (result != null) {
                    CompatLogin.LOGGER.info(
                        "Authenticated {} ({}) via {}",
                        result.getName(),
                        result.getId(),
                        provider.name
                    );
                    return result;
                }
            } catch (IOException | IllegalArgumentException | JsonParseException exception) {
                String reason = readableMessage(exception);
                failures.add(provider.name + ": " + reason);
                logProviderWarning(provider, reason);
            }
        }

        if (!failures.isEmpty()) {
            throw new AuthenticationServiceUnavailableException(
                "One or more configured authentication services were unavailable: " + join(failures)
            );
        }
        return null;
    }

    private AuthenticatedProfile query(Provider provider, String username, String serverId, InetAddress address)
        throws IOException {
        URI requestUri = createRequestUri(provider.endpoint, username, serverId, address);
        HttpURLConnection connection = (HttpURLConnection) requestUri.toURL().openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(connectTimeoutMillis);
        connection.setReadTimeout(requestTimeoutMillis);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", USER_AGENT);

        try {
            int status = connection.getResponseCode();
            if (status == HttpURLConnection.HTTP_NO_CONTENT || status == HttpURLConnection.HTTP_NOT_FOUND) {
                closeQuietly(connection.getErrorStream());
                return null;
            }
            if (status != HttpURLConnection.HTTP_OK) {
                closeQuietly(connection.getErrorStream());
                throw new IOException("HTTP " + status + " from " + provider.endpoint.getHost());
            }

            InputStream body = connection.getInputStream();
            try {
                String json = new String(readBounded(body), StandardCharsets.UTF_8);
                return parseProfile(json, username);
            } finally {
                body.close();
            }
        } finally {
            connection.disconnect();
        }
    }

    private byte[] readBounded(InputStream body) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxResponseBytes, 8_192));
        byte[] buffer = new byte[8_192];
        int total = 0;
        int read;
        while ((read = body.read(buffer)) >= 0) {
            total += read;
            if (total > maxResponseBytes) {
                throw new IOException("response exceeds authentication.maxResponseBytes (" + maxResponseBytes + ")");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static URI createRequestUri(URI endpoint, String username, String serverId, InetAddress address) {
        StringBuilder query = new StringBuilder(endpoint.toASCIIString())
            .append("?username=").append(encode(username))
            .append("&serverId=").append(encode(serverId));
        if (address != null) {
            query.append("&ip=").append(encode(address.getHostAddress()));
        }
        return URI.create(query.toString());
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException impossible) {
            throw new AssertionError("UTF-8 is required by every Java runtime", impossible);
        }
    }

    private static AuthenticatedProfile parseProfile(String json, String requestedUsername) {
        JsonElement root = new JsonParser().parse(json);
        if (!root.isJsonObject()) {
            throw new JsonParseException("hasJoined response must be a JSON object");
        }
        JsonObject object = root.getAsJsonObject();
        String idValue = requiredString(object, "id");
        String profileName = requiredString(object, "name");
        if (!profileName.equalsIgnoreCase(requestedUsername)) {
            throw new JsonParseException(
                "hasJoined response name '" + profileName + "' does not match requested username '" + requestedUsername + "'"
            );
        }

        List<AuthenticatedProfile.ProfileProperty> properties = new ArrayList<AuthenticatedProfile.ProfileProperty>();
        JsonElement propertiesElement = object.get("properties");
        if (propertiesElement != null && !propertiesElement.isJsonNull()) {
            if (!propertiesElement.isJsonArray()) {
                throw new JsonParseException("hasJoined response properties must be an array");
            }
            JsonArray propertyArray = propertiesElement.getAsJsonArray();
            if (propertyArray.size() > 128) {
                throw new JsonParseException("hasJoined response contains more than 128 properties");
            }
            for (int index = 0; index < propertyArray.size(); index++) {
                JsonElement propertyElement = propertyArray.get(index);
                if (!propertyElement.isJsonObject()) {
                    throw new JsonParseException("properties[" + index + "] must be an object");
                }
                JsonObject propertyObject = propertyElement.getAsJsonObject();
                String name = requiredString(propertyObject, "name");
                String value = requiredString(propertyObject, "value");
                JsonElement signatureElement = propertyObject.get("signature");
                String signature = null;
                if (signatureElement != null && !signatureElement.isJsonNull()) {
                    if (!signatureElement.isJsonPrimitive() || !signatureElement.getAsJsonPrimitive().isString()) {
                        throw new JsonParseException("properties[" + index + "].signature must be a string");
                    }
                    signature = signatureElement.getAsString();
                }
                properties.add(new AuthenticatedProfile.ProfileProperty(name, value, signature));
            }
        }

        return new AuthenticatedProfile(parseUuid(idValue), profileName, properties);
    }

    private static String requiredString(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new JsonParseException("hasJoined response field '" + field + "' must be a string");
        }
        String string = value.getAsString();
        if (isBlank(string)) {
            throw new JsonParseException("hasJoined response field '" + field + "' must not be blank");
        }
        return string;
    }

    private static UUID parseUuid(String value) {
        String compact = value.replace("-", "");
        if (!compact.matches("[0-9a-fA-F]{32}")) {
            throw new JsonParseException("hasJoined response id is not a UUID: " + value);
        }
        String canonical = compact.substring(0, 8) + "-"
            + compact.substring(8, 12) + "-"
            + compact.substring(12, 16) + "-"
            + compact.substring(16, 20) + "-"
            + compact.substring(20);
        return UUID.fromString(canonical.toLowerCase(Locale.ROOT));
    }

    private static void logProviderWarning(Provider provider, String reason) {
        long now = System.nanoTime();
        long previous = provider.lastWarningNanos.get();
        if ((previous == 0L || now - previous >= WARNING_INTERVAL_NANOS)
            && provider.lastWarningNanos.compareAndSet(previous, now)) {
            CompatLogin.LOGGER.warn(
                "[WARNING] authentication.services[{}] ({}): hasJoined request failed: {}",
                provider.configIndex,
                provider.name,
                reason
            );
        }
    }

    private static void closeQuietly(InputStream stream) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (IOException ignored) {
            // The response is already being discarded.
        }
    }

    private static String join(List<String> values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() > 0) {
                result.append("; ");
            }
            result.append(value);
        }
        return result.toString();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /** Reports the installed mod version to identity providers; falls back outside a Fabric runtime. */
    private static String modVersion() {
        try {
            ModContainer container = FabricLoader.getInstance().getModContainer(CompatLogin.MOD_ID).orElse(null);
            return container == null ? "dev" : container.getMetadata().getVersion().getFriendlyString();
        } catch (RuntimeException | LinkageError unavailable) {
            return "dev";
        }
    }

    private static String readableMessage(Exception exception) {
        String message = exception.getMessage();
        return isBlank(message) ? exception.getClass().getSimpleName() : message;
    }

    private static final class Provider {
        private final int configIndex;
        private final String name;
        private final URI endpoint;
        private final AtomicLong lastWarningNanos = new AtomicLong();

        private Provider(int configIndex, String name, URI endpoint) {
            this.configIndex = configIndex;
            this.name = name;
            this.endpoint = endpoint;
        }
    }
}
