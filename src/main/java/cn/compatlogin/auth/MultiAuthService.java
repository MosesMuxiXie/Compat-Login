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
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class MultiAuthService {
    private static final long WARNING_INTERVAL_NANOS = 30L * 1_000_000_000L;
    private static final String USER_AGENT = "Compat-Login/" + modVersion();
    /** Runs the parallel provider queries; daemon threads so a server shutdown is never delayed. */
    private static final ExecutorService AUTH_EXECUTOR = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "Compat Login Auth");
        thread.setDaemon(true);
        return thread;
    });

    private final int connectTimeoutMillis;
    private final int requestTimeoutMillis;
    private final int overallTimeoutMillis;
    private final int maxResponseBytes;
    private final List<Provider> providers;

    public MultiAuthService(CompatLoginConfig.Authentication config) {
        this.connectTimeoutMillis = config.connectTimeoutSeconds * 1_000;
        this.requestTimeoutMillis = config.requestTimeoutSeconds * 1_000;
        this.overallTimeoutMillis = config.overallTimeoutSeconds * 1_000;
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
        if (Thread.currentThread().isInterrupted()) {
            throw new AuthenticationServiceUnavailableException("Authentication request was interrupted");
        }

        // All providers are queried in parallel so one slow or unreachable provider (e.g. Mojang
        // from China) cannot delay logins of every other provider's players. Each query still
        // respects connectTimeout/requestTimeout; the whole wait is additionally capped by
        // overallTimeout. The first success wins, which is safe because the serverId is single-use
        // and only the provider the client actually joined returns a profile.
        CompletionService<Outcome> completion = new ExecutorCompletionService<Outcome>(AUTH_EXECUTOR);
        List<Future<Outcome>> futures = new ArrayList<Future<Outcome>>();
        for (Provider provider : providers) {
            futures.add(completion.submit(() -> queryOutcome(provider, username, serverId, address)));
        }

        List<String> failures = new ArrayList<String>();
        long deadline = System.currentTimeMillis() + overallTimeoutMillis;
        int received = 0;
        try {
            while (received < futures.size()) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    break;
                }
                Future<Outcome> done = completion.poll(remaining, TimeUnit.MILLISECONDS);
                if (done == null) {
                    break;
                }
                received++;
                Outcome outcome;
                try {
                    outcome = done.get();
                } catch (ExecutionException failure) {
                    Throwable cause = failure.getCause() == null ? failure : failure.getCause();
                    throw new IllegalStateException("Unexpected authentication failure", cause);
                }
                if (outcome.profile != null) {
                    CompatLogin.LOGGER.info(
                        "Authenticated {} ({}) via {}",
                        outcome.profile.getName(),
                        outcome.profile.getId(),
                        outcome.providerName
                    );
                    return outcome.profile;
                }
                if (outcome.failure != null) {
                    failures.add(outcome.providerName + ": " + outcome.failure);
                }
            }
        } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
            throw new AuthenticationServiceUnavailableException("Authentication request was interrupted");
        } finally {
            // Loser queries are not interruptible mid-read; they finish on their own timeouts in
            // the pool and their results are discarded. Cancelling still frees queued tasks.
            for (Future<Outcome> future : futures) {
                future.cancel(true);
            }
        }

        if (received < futures.size()) {
            throw new AuthenticationServiceUnavailableException(
                "Authentication did not finish within authentication.overallTimeoutSeconds ("
                    + (overallTimeoutMillis / 1_000) + "s)"
            );
        }
        if (!failures.isEmpty()) {
            throw new AuthenticationServiceUnavailableException(
                "One or more configured authentication services were unavailable: " + join(failures)
            );
        }
        return null;
    }

    private Outcome queryOutcome(Provider provider, String username, String serverId, InetAddress address) {
        try {
            AuthenticatedProfile profile = query(provider, username, serverId, address);
            return profile == null ? Outcome.miss() : Outcome.success(provider.name, profile);
        } catch (IOException | IllegalArgumentException | JsonParseException exception) {
            String reason = readableMessage(exception);
            logProviderWarning(provider, reason);
            return Outcome.failure(provider.name, reason);
        }
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

    /** The result of one provider query: either a profile, a rejection, or a transport failure. */
    private static final class Outcome {
        final String providerName;
        final AuthenticatedProfile profile;
        final String failure;

        private Outcome(String providerName, AuthenticatedProfile profile, String failure) {
            this.providerName = providerName;
            this.profile = profile;
            this.failure = failure;
        }

        static Outcome success(String providerName, AuthenticatedProfile profile) {
            return new Outcome(providerName, profile, null);
        }

        static Outcome miss() {
            return new Outcome(null, null, null);
        }

        static Outcome failure(String providerName, String failure) {
            return new Outcome(providerName, null, failure);
        }
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
