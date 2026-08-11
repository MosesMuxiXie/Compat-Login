package cn.compatlogin.auth;

import cn.compatlogin.CompatLogin;
import cn.compatlogin.config.CompatLoginConfig;
import cn.compatlogin.config.YggdrasilEndpoint;
import com.google.common.collect.ImmutableMultimap;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.exceptions.AuthenticationUnavailableException;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import com.mojang.authlib.yggdrasil.ProfileResult;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

public final class MultiAuthService {
    private static final long WARNING_INTERVAL_NANOS = Duration.ofSeconds(30).toNanos();

    private final HttpClient httpClient;
    private final Duration requestTimeout;
    private final int maxResponseBytes;
    private final List<Provider> providers;

    public MultiAuthService(CompatLoginConfig.Authentication config) {
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(config.connectTimeoutSeconds))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
        this.requestTimeout = Duration.ofSeconds(config.requestTimeoutSeconds);
        this.maxResponseBytes = config.maxResponseBytes;

        List<Provider> enabledProviders = new ArrayList<>();
        for (int index = 0; index < config.services.size(); index++) {
            CompatLoginConfig.Service service = config.services.get(index);
            if (Boolean.TRUE.equals(service.enabled)) {
                enabledProviders.add(new Provider(
                    index,
                    service.name,
                    YggdrasilEndpoint.resolve(service.hasJoinedUrl),
                    new AtomicLong()
                ));
            }
        }
        this.providers = List.copyOf(enabledProviders);
    }

    public int providerCount() {
        return providers.size();
    }

    public ProfileResult hasJoinedServer(String username, String serverId, InetAddress address)
        throws AuthenticationUnavailableException {
        List<String> failures = new ArrayList<>();
        for (Provider provider : providers) {
            try {
                Optional<ProfileResult> result = query(provider, username, serverId, address);
                if (result.isPresent()) {
                    GameProfile profile = result.get().profile();
                    CompatLogin.LOGGER.info(
                        "Authenticated {} ({}) via {}",
                        profile.name(),
                        profile.id(),
                        provider.name()
                    );
                    return result.get();
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AuthenticationUnavailableException("Authentication request was interrupted", exception);
            } catch (IOException | IllegalArgumentException | JsonParseException exception) {
                String reason = readableMessage(exception);
                failures.add(provider.name() + ": " + reason);
                logProviderWarning(provider, reason);
            }
        }

        if (!failures.isEmpty()) {
            throw new AuthenticationUnavailableException(
                "One or more configured authentication services were unavailable: " + String.join("; ", failures)
            );
        }
        return null;
    }

    private Optional<ProfileResult> query(Provider provider, String username, String serverId, InetAddress address)
        throws IOException, InterruptedException {
        URI requestUri = createRequestUri(provider.endpoint(), username, serverId, address);
        HttpRequest request = HttpRequest.newBuilder(requestUri)
            .timeout(requestTimeout)
            .header("Accept", "application/json")
            .header("User-Agent", "Compat-Login/0.1")
            .GET()
            .build();

        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        int status = response.statusCode();
        try (InputStream body = response.body()) {
            if (status == 204 || status == 404) {
                return Optional.empty();
            }
            if (status != 200) {
                throw new IOException("HTTP " + status + " from " + provider.endpoint().getHost());
            }

            byte[] bytes = body.readNBytes(maxResponseBytes + 1);
            if (bytes.length > maxResponseBytes) {
                throw new IOException("response exceeds authentication.maxResponseBytes (" + maxResponseBytes + ")");
            }
            String json = new String(bytes, StandardCharsets.UTF_8);
            return Optional.of(parseProfile(json, username));
        }
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
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static ProfileResult parseProfile(String json, String requestedUsername) {
        JsonElement root = JsonParser.parseString(json);
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

        ImmutableMultimap.Builder<String, Property> properties = ImmutableMultimap.builder();
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
                Property property;
                if (signatureElement == null || signatureElement.isJsonNull()) {
                    property = new Property(name, value);
                } else if (signatureElement.isJsonPrimitive() && signatureElement.getAsJsonPrimitive().isString()) {
                    property = new Property(name, value, signatureElement.getAsString());
                } else {
                    throw new JsonParseException("properties[" + index + "].signature must be a string");
                }
                properties.put(name, property);
            }
        }

        UUID id = parseUuid(idValue);
        GameProfile profile = new GameProfile(id, profileName, new PropertyMap(properties.build()));
        return new ProfileResult(profile);
    }

    private static String requiredString(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new JsonParseException("hasJoined response field '" + field + "' must be a string");
        }
        String string = value.getAsString();
        if (string.isBlank()) {
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
        long previous = provider.lastWarningNanos().get();
        if ((previous == 0L || now - previous >= WARNING_INTERVAL_NANOS)
            && provider.lastWarningNanos().compareAndSet(previous, now)) {
            CompatLogin.LOGGER.warn(
                "[WARNING] authentication.services[{}] ({}): hasJoined request failed: {}",
                provider.configIndex(),
                provider.name(),
                reason
            );
        }
    }

    private static String readableMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private record Provider(int configIndex, String name, URI endpoint, AtomicLong lastWarningNanos) {
    }
}
