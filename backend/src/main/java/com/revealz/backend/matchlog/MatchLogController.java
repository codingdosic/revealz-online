package com.revealz.backend.matchlog;

import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.revealz.backend.web.ApiException;
import com.revealz.backend.worker.WorkerManager;
import tools.jackson.databind.JsonNode;

@RestController
class MatchLogController {
    private final MatchLogService service;
    private final WorkerManager workers;
    MatchLogController(MatchLogService service, WorkerManager workers) { this.service = service; this.workers = workers; }

    @PostMapping("/v1/internal/matches/{matchId}/logs/final")
    ResponseEntity<Map<String, Object>> store(@PathVariable UUID matchId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody JsonNode body) {
        String token = authorization != null && authorization.startsWith("Bearer ") ? authorization.substring(7).trim() : "";
        if (!workers.authenticate(matchId, token)) throw new ApiException(HttpStatus.UNAUTHORIZED, "worker_unauthorized");
        MatchLogService.Result result = service.store(body, matchId);
        Map<String, Object> response = Map.of("stored", result.stored(), "duplicate", result.duplicate(), "matchId", result.matchId());
        return ResponseEntity.status(result.duplicate() ? HttpStatus.OK : HttpStatus.CREATED).body(response);
    }
}
