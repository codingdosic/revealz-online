package com.revealz.backend.lobby;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import com.revealz.backend.ops.MaintenanceStore;
import com.revealz.backend.worker.WorkerManager;

@Service
public class HealthService {
    private final LobbyService lobby;
    private final WorkerManager workers;
    private final MaintenanceStore maintenance;

    HealthService(LobbyService lobby, WorkerManager workers, MaintenanceStore maintenance) {
        this.lobby = lobby;
        this.workers = workers;
        this.maintenance = maintenance;
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> workerState = workers.status();
        Map<String, Object> maintenanceState = maintenance.read();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("rooms", workerState.get("rooms"));
        result.put("freePorts", workerState.get("freePorts"));
        result.put("ttlMs", workers.properties().getRoomTtlMs());
        result.put("queueSize", lobby.queueSize());
        result.put("matchTimeoutMs", workers.properties().getMatchTimeoutMs());
        result.put("warmReady", workerState.get("warmReady"));
        result.put("warmSpawning", workerState.get("warmSpawning"));
        result.put("warmTarget", workerState.get("warmTarget"));
        result.put("metaDb", true);
        result.put("version", Map.of("protocol", 1, "lobby", "0.2.0-spring"));
        result.put("worker", workers.fingerprint());
        result.put("maintenance", maintenanceState.get("enabled"));
        result.put("maintenanceMessage", maintenanceState.get("message"));
        return result;
    }
}
