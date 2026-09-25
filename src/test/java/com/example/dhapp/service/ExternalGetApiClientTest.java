package com.example.dhapp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.example.dhapp.controller.ExternalGetApiController;
import com.example.dhapp.dto.ExternalGetApiResponse;
import com.example.dhapp.service.ExternalGetApiClient.GetResult;
import com.sun.net.httpserver.HttpServer;

/**
 * {@link ExternalGetApiClient} が HTTP GET のステータスと本文の先頭を返すことの確認。
 */
class ExternalGetApiClientTest {

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        respond("/long", 200, "text/plain; charset=UTF-8", "あいうえお" + "x".repeat(10_000), StandardCharsets.UTF_8);
        respond("/exact", 200, "text/plain", "0123456789", StandardCharsets.UTF_8);
        respond("/sjis", 200, "text/plain; charset=Shift_JIS", "日本語", Charset.forName("Shift_JIS"));
        respond("/missing", 404, "application/json", "{\"error\":\"not found\"}", StandardCharsets.UTF_8);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void respond(String path, int status, String contentType, String body, Charset charset) {
        server.createContext(path, exchange -> {
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            byte[] bytes = body.getBytes(charset);
            exchange.getResponseHeaders().add("Content-Type", contentType);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
    }

    private ExternalGetApiClient client(String path, int bodyHeadChars) {
        return new ExternalGetApiClient(baseUrl + path, 2000, 5000, bodyHeadChars);
    }

    @Test
    void returnsStatusAndOnlyTheHeadOfALongBody() {
        GetResult result = client("/long", 10).callExternalApi("req-1");

        assertEquals(200, result.statusCode());
        assertEquals("あいうえおxxxxx", result.bodyHead());
        assertTrue(result.bodyTruncated());
        assertEquals("text/plain; charset=UTF-8", result.contentType());
        assertEquals(15 + 10_000L, result.contentLength());
    }

    @Test
    void bodyExactlyAtTheLimitIsNotTruncated() {
        GetResult result = client("/exact", 10).callExternalApi("req-2");

        assertEquals("0123456789", result.bodyHead());
        assertFalse(result.bodyTruncated());
    }

    @Test
    void decodesWithCharsetFromContentType() {
        GetResult result = client("/sjis", 100).callExternalApi("req-3");

        assertEquals("日本語", result.bodyHead());
        assertFalse(result.bodyTruncated());
    }

    @Test
    void errorStatusIsReturnedWithItsBody() {
        GetResult result = client("/missing", 100).callExternalApi("req-4");

        assertEquals(404, result.statusCode());
        assertEquals("{\"error\":\"not found\"}", result.bodyHead());
    }

    @Test
    void controllerReportsConnectionFailureInBody() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        ExternalGetApiClient unreachable =
                new ExternalGetApiClient("http://127.0.0.1:" + closedPort + "/health", 1000, 1000, 100);

        ExternalGetApiResponse response = new ExternalGetApiController(unreachable).execute().getBody();

        assertEquals("EXTERNAL_API_FAILED", response.getStatus());
        assertNull(response.getExternalApiStatus());
        assertTrue(response.getMessage() != null && !response.getMessage().isEmpty());
    }
}
