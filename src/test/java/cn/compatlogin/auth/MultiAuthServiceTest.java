package cn.compatlogin.auth;

import cn.compatlogin.config.CompatLoginConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiAuthServiceTest {
    private static final String PROFILE_JSON = """
        {
          "id": "069a79f444e94726a5befca90e38aaf5",
          "name": "Notch",
          "properties": [
            {"name": "textures", "value": "encoded-texture", "signature": "signature"}
          ]
        }
        """;

    private HttpServer server;
    private ExecutorService serverExecutor;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
        if (serverExecutor != null) {
            serverExecutor.shutdownNow();
        }
    }

    @Test
    void parsesProfileFromAnyProvider() throws Exception {
        server = createServer();
        server.createContext("/first/session/minecraft/hasJoined", exchange -> respond(exchange, 204, ""));
        server.createContext("/second/session/minecraft/hasJoined", exchange -> respond(exchange, 200, PROFILE_JSON));
        startServer();

        MultiAuthService service = new MultiAuthService(configuration(server.getAddress().getPort(), false));
        AuthenticatedProfile result = service.hasJoinedServer("Notch", "server-hash", null);

        assertNotNull(result);
        assertEquals("069a79f4-44e9-4726-a5be-fca90e38aaf5", result.getId().toString());
        assertEquals("Notch", result.getName());
        assertEquals(1, result.getProperties().size());
        assertEquals("textures", result.getProperties().get(0).getName());
        assertEquals("encoded-texture", result.getProperties().get(0).getValue());
        assertEquals("signature", result.getProperties().get(0).getSignature());
    }

    @Test
    void returnsNullWhenEveryProviderRejectsTheSession() throws Exception {
        server = createServer();
        server.createContext("/first/session/minecraft/hasJoined", exchange -> respond(exchange, 204, ""));
        server.createContext("/second/session/minecraft/hasJoined", exchange -> respond(exchange, 404, ""));
        startServer();

        MultiAuthService service = new MultiAuthService(configuration(server.getAddress().getPort(), false));
        assertNull(service.hasJoinedServer("Nobody", "server-hash", null));
    }

    @Test
    void returnsFirstSuccessfulProviderWithoutWaitingForTheSlowestOne() throws Exception {
        server = createServer();
        // The first-listed provider is slow; a sequential implementation would wait for it first.
        server.createContext("/first/session/minecraft/hasJoined", exchange -> {
            sleep(1_500);
            respond(exchange, 204, "");
        });
        server.createContext("/second/session/minecraft/hasJoined", exchange -> respond(exchange, 200, PROFILE_JSON));
        startServer();

        MultiAuthService service = new MultiAuthService(configuration(server.getAddress().getPort(), false));
        long started = System.currentTimeMillis();
        AuthenticatedProfile result = service.hasJoinedServer("Notch", "server-hash", null);
        long elapsed = System.currentTimeMillis() - started;

        assertNotNull(result);
        assertEquals("Notch", result.getName());
        assertTrue(elapsed < 1_000,
            "providers must be queried in parallel; took " + elapsed + "ms although one provider answered immediately");
    }

    @Test
    void singleProviderReturnsProfileDirectly() throws Exception {
        server = createServer();
        server.createContext("/first/session/minecraft/hasJoined", exchange -> respond(exchange, 200, PROFILE_JSON));
        startServer();

        MultiAuthService service = new MultiAuthService(configuration(server.getAddress().getPort(), true));
        AuthenticatedProfile result = service.hasJoinedServer("Notch", "server-hash", null);

        assertNotNull(result);
        assertEquals("Notch", result.getName());
    }

    @Test
    void aggregatesFailuresWhenSomeProvidersAreUnavailable() throws Exception {
        server = createServer();
        server.createContext("/first/session/minecraft/hasJoined", exchange -> {
            throw new IOException("broken provider");
        });
        server.createContext("/second/session/minecraft/hasJoined", exchange -> respond(exchange, 204, ""));
        startServer();

        MultiAuthService service = new MultiAuthService(configuration(server.getAddress().getPort(), false));
        AuthenticationServiceUnavailableException failure = assertThrows(
            AuthenticationServiceUnavailableException.class,
            () -> service.hasJoinedServer("Nobody", "server-hash", null)
        );

        assertTrue(failure.getMessage().contains("First"), "message was: " + failure.getMessage());
    }

    @Test
    void throwsWhenTheOverallDeadlineExpiresBeforeAnyProviderAnswers() throws Exception {
        server = createServer();
        server.createContext("/first/session/minecraft/hasJoined", exchange -> {
            sleep(4_000);
            respond(exchange, 200, PROFILE_JSON);
        });
        startServer();

        CompatLoginConfig.Authentication authentication = configuration(server.getAddress().getPort(), true);
        authentication.connectTimeoutSeconds = 10;
        authentication.requestTimeoutSeconds = 10;
        authentication.overallTimeoutSeconds = 1;
        MultiAuthService service = new MultiAuthService(authentication);

        long started = System.currentTimeMillis();
        AuthenticationServiceUnavailableException failure = assertThrows(
            AuthenticationServiceUnavailableException.class,
            () -> service.hasJoinedServer("Notch", "server-hash", null)
        );
        long elapsed = System.currentTimeMillis() - started;

        assertTrue(failure.getMessage().contains("overallTimeoutSeconds"), "message was: " + failure.getMessage());
        assertTrue(elapsed < 3_000,
            "the overall deadline must cut the wait short; took " + elapsed + "ms");
    }

    private static HttpServer createServer() throws IOException {
        return HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    }

    /** HttpServer serializes requests on its default executor; the tests need real concurrency. */
    private void startServer() throws IOException {
        serverExecutor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "MultiAuthServiceTest");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(serverExecutor);
        server.start();
    }

    private static CompatLoginConfig.Authentication configuration(int port, boolean oneProvider) {
        CompatLoginConfig.Authentication authentication = new CompatLoginConfig.Authentication();
        authentication.allowInsecureHttp = true;
        authentication.connectTimeoutSeconds = 2;
        authentication.requestTimeoutSeconds = 5;
        authentication.services.add(new CompatLoginConfig.Service(
            "First",
            true,
            "http://127.0.0.1:" + port + "/first/session/minecraft/hasJoined"
        ));
        if (!oneProvider) {
            authentication.services.add(new CompatLoginConfig.Service(
                "Second",
                true,
                "http://127.0.0.1:" + port + "/second/session/minecraft/hasJoined"
            ));
        }
        return authentication;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while simulating a slow provider", interruption);
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }
}
