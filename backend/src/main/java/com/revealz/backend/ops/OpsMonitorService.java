package com.revealz.backend.ops;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import com.revealz.backend.lobby.HealthService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
class OpsMonitorService {
    private static final int RECENT_LINES = 120;
    private final Path dataDir;
    private final HealthService health;
    private final JsonMapper json;

    OpsMonitorService(@Value("${ops.data-dir:./ops-data}") String dataDir,
            HealthService health, JsonMapper json) {
        this.dataDir = Path.of(dataDir).toAbsolutePath().normalize();
        this.health = health;
        this.json = json;
    }

    Map<String, Object> payload() {
        List<Object> recent = recent();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("now", health.snapshot());
        result.put("recent", recent);
        result.put("collected", collectedLabel(recent, json));
        result.put("logPath", "ops-data/health.jsonl");
        return result;
    }

    String monitorPage() {
        try { return new ClassPathResource("ops_monitor.html").getContentAsString(java.nio.charset.StandardCharsets.UTF_8); }
        catch (IOException exception) { return "<!doctype html><meta charset=\"utf-8\"><p>ops_monitor.html을 읽을 수 없습니다.</p>"; }
    }

    String dbPage() {
        try { return new ClassPathResource("ops_db.html").getContentAsString(java.nio.charset.StandardCharsets.UTF_8); }
        catch (IOException exception) { return "<!doctype html><meta charset=\"utf-8\"><p>ops_db.html을 읽을 수 없습니다.</p>"; }
    }

    Map<String, Object> writePrecheck() {
        Map<String, Object> body = Map.of("ts", java.time.Instant.now().toString(), "health", health.snapshot());
        String name = "precheck-" + java.time.Instant.now().toString().replace(':', '-').replace('.', '-') + ".json";
        try {
            Files.createDirectories(dataDir);
            Files.writeString(dataDir.resolve(name), json.writeValueAsString(body));
            return Map.of("ok", true, "file", name, "health", body.get("health"));
        } catch (IOException exception) { throw new IllegalStateException("precheck_write_failed", exception); }
    }

    private List<Object> recent() {
        Path path = dataDir.resolve("health.jsonl");
        if (!Files.isRegularFile(path)) return List.of();
        try {
            List<String> lines = Files.readAllLines(path);
            List<Object> result = new ArrayList<>();
            for (String line : lines.subList(Math.max(0, lines.size() - RECENT_LINES), lines.size())) {
                if (line.isBlank()) continue;
                try { result.add(json.treeToValue(json.readTree(line), Object.class)); }
                catch (RuntimeException ignored) { }
            }
            return result;
        } catch (IOException exception) { return List.of(); }
    }

    static String collectedLabel(List<Object> recent, JsonMapper json) {
        if (recent.isEmpty()) return "폴러 기록 없음 — tools/ops_health_poll.py를 확인하세요";
        JsonNode last = json.valueToTree(recent.getLast());
        String value = last.path("ts").asText(last.path("time").asText("")).trim();
        try {
            Instant collectedAt = Instant.parse(value);
            long minutes = Math.max(0, Duration.between(collectedAt, Instant.now()).toMinutes());
            return "마지막 수집: " + collectedAt + (minutes == 0 ? " (방금)" : " (" + minutes + "분 전)");
        } catch (RuntimeException exception) { return "마지막 수집 시각을 읽지 못함"; }
    }
}
