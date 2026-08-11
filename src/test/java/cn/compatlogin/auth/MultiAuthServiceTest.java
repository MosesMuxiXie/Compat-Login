package cn.compatlogin.auth;

import cn.compatlogin.config.CompatLoginConfig;
import com.mojang.authlib.yggdrasil.ProfileResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiAuthServiceTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void fallsBackToSecondProviderAndParsesProfile() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/first/session/minecraft/hasJoined", exchange -> respond(exchange, 204, ""));
        server.createContext("/second/session/minecraft/hasJoined", exchange -> respond(exchange, 200, """
            {
              "id": "069a79f444e94726a5befca90e38aaf5",
              "name": "Notch",
              "properties": [
                {"name": "textures", "value": "encoded-texture", "signature": "signature"}
              ]
            }
            """));
        server.start();

        MultiAuthService service = new MultiAuthService(configuration(server.getAddress().getPort(), false));
        ProfileResult result = service.hasJoinedServer("Notch", "server-hash", null);

        assertNotNull(result);
        assertEquals("069a79f4-44e9-4726-a5be-fca90e38aaf5", result.profile().id().toString());
        assertEquals("Notch", result.profile().name());
        assertTrue(result.profile().properties().containsKey("textures"));
    }

    @Test
    void returnsNullWhenEveryProviderRejectsTheSession() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/first/session/minecraft/hasJoined", exchange -> respond(exchange, 204, ""));
        server.createContext("/second/session/minecraft/hasJoined", exchange -> respond(exchange, 404, ""));
        server.start();

        MultiAuthService service = new MultiAuthService(configuration(server.getAddress().getPort(), false));
        assertNull(service.hasJoinedServer("Nobody", "server-hash", null));
    }

    private static CompatLoginConfig.Authentication configuration(int port, boolean oneProvider) {
        CompatLoginConfig.Authentication authentication = new CompatLoginConfig.Authentication();
        authentication.allowInsecureHttp = true;
        authentication.connectTimeoutSeconds = 2;
        authentication.requestTimeoutSeconds = 2;
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
