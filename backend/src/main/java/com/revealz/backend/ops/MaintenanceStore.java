package com.revealz.backend.ops;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class MaintenanceStore {
    private final Path path;
    private final JsonMapper jsonMapper;

    MaintenanceStore(@Value("${ops.data-dir:./ops-data}") String dataDir, JsonMapper jsonMapper) {
        path = Path.of(dataDir).toAbsolutePath().normalize().resolve("maintenance.json");
        this.jsonMapper = jsonMapper;
    }

    public Map<String, Object> read() {
        try {
            JsonNode node = jsonMapper.readTree(Files.readString(path));
            boolean enabled = node.path("enabled").booleanValue();
            return Map.of("enabled", enabled, "message", enabled ? node.path("message").stringValue() : "");
        } catch (IOException | RuntimeException exception) {
            return Map.of("enabled", false, "message", "");
        }
    }

    public Map<String, Object> write(boolean enabled, String message) {
        Map<String, Object> body = Map.of("enabled", enabled, "message", enabled ? message : "", "updatedAt", Instant.now().toString());
        try {
            Files.createDirectories(path.getParent());
            Path temporary = path.resolveSibling("maintenance.json.tmp");
            Files.writeString(temporary, jsonMapper.writeValueAsString(body));
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return body;
        } catch (IOException exception) {
            throw new IllegalStateException("maintenance_write_failed", exception);
        }
    }
}
