package com.whatsappscheduler.whatsapp_scheduler;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

import tools.jackson.databind.json.JsonMapper;
import com.whatsappscheduler.whatsapp_scheduler.whatsapp.*;

class WorkerClientTests {
    private static final String TOKEN = "local-test-token-with-at-least-32-characters";

    @Test
    void sendsExpectedContractAndAuthentication() throws Exception {
        var body = new AtomicReference<String>();
        var authorization = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/messages", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] response = "{\"executionId\":\"exec-1\",\"status\":\"sent\",\"messageId\":\"wa-1\",\"replayed\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var client = new WorkerClient("http://127.0.0.1:" + server.getAddress().getPort(), TOKEN);
            var result = client.send("exec-1", "123@g.us", "Mensagem com acentuação", Instant.parse("2026-11-02T12:30:00Z"));
            var request = JsonMapper.builder().build().readTree(body.get());
            assertEquals("Bearer " + TOKEN, authorization.get());
            assertEquals("exec-1", request.path("executionId").asString());
            assertEquals("2026-11-02T12:30:00Z", request.path("sendBefore").asString());
            assertEquals("123@g.us", request.path("groupId").asString());
            assertEquals("Mensagem com acentuação", request.path("text").asString());
            assertTrue(result.replayed());
            assertEquals("wa-1", result.messageId());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void workerFailuresKeepSafeCodesAndMalformedSuccessIsUncertain() throws Exception {
        var response = new AtomicReference<>("{\"code\":\"SEND_UNCERTAIN\",\"message\":\"private details\"}");
        var code = new java.util.concurrent.atomic.AtomicInteger(409);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/messages", exchange -> {
            byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(code.get(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var client = new WorkerClient("http://127.0.0.1:" + server.getAddress().getPort(), TOKEN);
            var error = assertThrows(WorkerException.class, () -> client.send("exec-1", "123@g.us", "Olá", Instant.parse("2026-11-02T12:30:00Z")));
            assertEquals("SEND_UNCERTAIN", error.code);
            assertTrue(error.uncertain());
            assertFalse(error.retryable());
            code.set(503);
            response.set("{\"code\":\"NOT_CONNECTED\"}");
            error = assertThrows(WorkerException.class, () -> client.send("exec-1", "123@g.us", "Olá", Instant.parse("2026-11-02T12:30:00Z")));
            assertTrue(error.retryable());
            assertFalse(error.uncertain());
            code.set(200);
            response.set("{}");
            error = assertThrows(WorkerException.class, () -> client.send("exec-1", "123@g.us", "Olá", Instant.parse("2026-11-02T12:30:00Z")));
            assertTrue(error.uncertain());
        } finally {
            server.stop(0);
        }
    }
}
