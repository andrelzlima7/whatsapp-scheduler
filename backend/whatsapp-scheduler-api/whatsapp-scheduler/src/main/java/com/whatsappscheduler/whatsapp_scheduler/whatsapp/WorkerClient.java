package com.whatsappscheduler.whatsapp_scheduler.whatsapp;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
import tools.jackson.databind.json.JsonMapper;

@Component
public class WorkerClient {
    public record Connection(String status, Integer lastDisconnectCode) {
    }

    public record Qr(String qrImage) {
    }

    public record Group(String id, String name, boolean announcementOnly) {
    }

    public record Groups(List<Group> groups) {
    }

    public record Sent(String executionId, String status, String messageId, boolean replayed) {
    }

    private final RestClient client;
    private final JsonMapper json = JsonMapper.builder().build();
    private static final Set<String> CODES = Set.of("BUSY", "NOT_CONNECTED", "GROUP_UNAVAILABLE", "GROUPS_UNAVAILABLE",
            "NOT_GROUP_MEMBER", "GROUP_RESTRICTED", "SEND_UNCERTAIN", "SEND_EXPIRED", "EXECUTION_CONFLICT", "QR_UNAVAILABLE", "INVALID_REQUEST", "UNAUTHORIZED");

    public WorkerClient(@Value("${app.worker.url}") String url, @Value("${app.worker.token}") String token) {
        var uri = URI.create(url);
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IllegalStateException("WHATSAPP_WORKER_URL inválida.");
        }
        if (token.isBlank() || token.length() < 32)
            throw new IllegalStateException("Configure WHATSAPP_WORKER_TOKEN com pelo menos 32 caracteres.");
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(60));
        client = RestClient.builder().baseUrl(url).requestFactory(factory).defaultHeader("Authorization", "Bearer " + token).build();
    }

    public Connection connection() {
        return get("/connection", Connection.class);
    }

    public Qr qr() {
        return get("/connection/qr", Qr.class);
    }

    public List<Group> groups() {
        var result = get("/groups", Groups.class);
        if (result.groups() == null || result.groups().stream().anyMatch(g -> g.id() == null || !g.id().matches("[0-9]+(-[0-9]+)?@g\\.us") || g.name() == null)) {
            throw new WorkerException(502, "WORKER_ERROR");
        }
        return result.groups();
    }

    public Sent send(String executionId, String groupId, String text, Instant sendBefore) {
        try {
            var result = client.post().uri("/messages").body(Map.of("executionId", executionId, "groupId", groupId, "text", text, "sendBefore", sendBefore.toString())).retrieve().body(Sent.class);
            if (result == null || !"sent".equals(result.status()) || !executionId.equals(result.executionId()) || result.messageId() == null || result.messageId().isBlank()) {
                throw new WorkerException(502, "WORKER_ERROR");
            }
            return result;
        } catch (RestClientResponseException e) {
            throw translate(e);
        } catch (RestClientException e) {
            throw new WorkerException(503, "TRANSPORT_ERROR");
        }
    }

    private <T> T get(String path, Class<T> type) {
        try {
            T result = client.get().uri(path).retrieve().body(type);
            if (result == null) throw new WorkerException(502, "WORKER_ERROR");
            return result;
        } catch (RestClientResponseException e) {
            throw translate(e);
        } catch (RestClientException e) {
            throw new WorkerException(503, "TRANSPORT_ERROR");
        }
    }

    private WorkerException translate(RestClientResponseException e) {
        String code = "WORKER_ERROR";
        try {
            String candidate = json.readTree(e.getResponseBodyAsString()).path("code").asString();
            if (CODES.contains(candidate)) code = candidate;
        } catch (RuntimeException ignored) {
        }
        int status = e.getStatusCode().value();
        return new WorkerException(status == 401 ? 502 : status, code);
    }
}
