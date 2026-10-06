package com.revealz.backend.matchlog;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.revealz.backend.web.ApiException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
class MatchLogService {
    private final MatchLogNormalizer normalizer;
    private final MatchLogRepository repository;
    private final JsonMapper jsonMapper;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    MatchLogService(MatchLogNormalizer normalizer, MatchLogRepository repository, JsonMapper jsonMapper) {
        this.normalizer = normalizer; this.repository = repository; this.jsonMapper = jsonMapper;
    }

    @Transactional
    Result store(JsonNode raw, java.util.UUID expected) {
        MatchLogNormalizer.Normalized payload = normalizer.normalize(raw, expected);
        JsonNode tree = jsonMapper.valueToTree(payload.payload());
        String canonical = jsonMapper.writeValueAsString(normalizer.canonical(tree));
        String hash = sha256(canonical);
        repository.lock(payload.matchId());
        String existing = repository.existingHash(payload.matchId());
        if (existing != null) {
            if (existing.equals(hash)) return new Result(true, true, payload.matchId());
            throw new ApiException(HttpStatus.CONFLICT, "match_payload_conflict");
        }
        String summary = jsonMapper.writeValueAsString(payload.summary());
        String players = jsonMapper.writeValueAsString(payload.summary().get("players"));
        repository.insertMatch(payload, hash, summary, players);
        if (!payload.events().isEmpty()) repository.insertEvents(payload.matchId(), jsonMapper.writeValueAsString(payload.events()));
        if (!payload.snapshots().isEmpty()) repository.insertSnapshots(payload.matchId(), jsonMapper.writeValueAsString(payload.snapshots()));
        return new Result(true, false, payload.matchId());
    }

    @PostConstruct void startPrune() { scheduler.scheduleWithFixedDelay(this::pruneQuietly, 0, 6, TimeUnit.HOURS); }
    private void pruneQuietly() { try { repository.prune(); } catch (RuntimeException ignored) { } }
    @PreDestroy void stop() { scheduler.shutdownNow(); }
    private String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    record Result(boolean stored, boolean duplicate, String matchId) { }
}
