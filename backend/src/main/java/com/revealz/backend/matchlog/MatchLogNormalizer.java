package com.revealz.backend.matchlog;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import com.revealz.backend.web.ApiException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Component
class MatchLogNormalizer {
    static final int SCHEMA_VERSION = 1;
    static final int MAX_EVENTS = 20_000;
    static final int MAX_SNAPSHOTS = 1_000;
    private static final Pattern UUID_PATTERN = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");

    Normalized normalize(JsonNode raw, UUID expected) {
        if (raw == null || !raw.isObject()) invalid();
        String matchId = requiredText(raw.get("matchId"), 36).toLowerCase();
        if (!UUID_PATTERN.matcher(matchId).matches() || !matchId.equals(expected.toString())) invalid();
        if (integer(raw.get("schemaVersion"), 1) != SCHEMA_VERSION) invalid();
        Map<String, Object> summary = summary(raw.get("summary"));
        List<Map<String, Object>> events = events(raw.get("events"), matchId);
        if ("completed".equals(summary.get("status")) && events.stream().noneMatch(event -> "MATCH_FINISHED".equals(event.get("type")))) invalid();
        long last = events.isEmpty() ? 0 : ((Number) events.getLast().get("seq")).longValue();
        List<Map<String, Object>> snapshots = snapshots(raw.get("snapshots"), matchId, last);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", SCHEMA_VERSION); payload.put("matchId", matchId);
        payload.put("summary", summary); payload.put("events", events); payload.put("snapshots", snapshots);
        return new Normalized(matchId, summary, events, snapshots, payload);
    }

    JsonNode canonical(JsonNode value) {
        if (value == null || !value.isObject()) {
            if (value != null && value.isArray()) {
                ArrayNode array = (ArrayNode) value.deepCopy();
                for (int index = 0; index < array.size(); index++) array.set(index, canonical(array.get(index)));
                return array;
            }
            return value;
        }
        ObjectNode object = (ObjectNode) value.deepCopy();
        ObjectNode sorted = object.objectNode();
        List<String> names = new ArrayList<>(); object.propertyStream().forEach(entry -> names.add(entry.getKey()));
        names.stream().sorted(Comparator.naturalOrder()).forEach(name -> sorted.set(name, canonical(object.get(name))));
        return sorted;
    }

    private Map<String, Object> summary(JsonNode raw) {
        if (raw == null || !raw.isObject()) invalid();
        String status = requiredText(raw.get("status"), 32);
        if (!Set.of("completed", "interrupted").contains(status)) invalid();
        Long started = optionalTime(raw.get("startedAtUnixMs"));
        Long ended = optionalTime(raw.get("endedAtUnixMs"));
        if (started != null && ended != null && ended < started) invalid();
        Integer winner = raw.hasNonNull("winnerSide") ? integer(raw.get("winnerSide"), 0) : null;
        if (winner != null && winner > 1) invalid();
        if ("completed".equals(status) && (started == null || ended == null || winner == null)) invalid();
        if ("interrupted".equals(status) && winner != null) invalid();
        List<Map<String, Object>> players = new ArrayList<>();
        JsonNode rawPlayers = raw.get("players");
        if (rawPlayers != null && !rawPlayers.isNull()) {
            if (!rawPlayers.isArray() || rawPlayers.size() > 2) invalid();
            Set<Integer> sides = new java.util.HashSet<>();
            for (JsonNode player : rawPlayers) {
                if (!player.isObject()) invalid();
                int side = integer(player.get("side"), 0);
                if (side > 1 || !sides.add(side)) invalid();
                players.add(Map.of("side", side, "accountKey", optionalText(player.get("accountKey"), 128),
                        "identityTrust", "worker_reported_unverified_account_key"));
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", status); result.put("startedAtUnixMs", started); result.put("endedAtUnixMs", ended);
        result.put("winnerSide", winner); result.put("reason", optionalText(raw.get("reason"), 128)); result.put("players", players);
        return result;
    }

    private List<Map<String, Object>> events(JsonNode raw, String matchId) {
        if (raw == null || !raw.isArray() || raw.size() > MAX_EVENTS) invalid();
        List<Map<String, Object>> result = new ArrayList<>();
        long previous = 0;
        for (JsonNode event : raw) {
            if (!event.isObject() || integer(event.get("schemaVersion"), 1) != SCHEMA_VERSION
                    || !matchId.equals(text(event.get("matchId")))) invalid();
            long seq = longInteger(event.get("seq"), 1);
            if (seq != previous + 1) invalid();
            previous = seq;
            Integer actor = event.hasNonNull("actorSide") ? integer(event.get("actorSide"), 0) : null;
            if (actor != null && actor > 1) invalid();
            if (event.get("payload") == null || !event.get("payload").isObject()) invalid();
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("schemaVersion", SCHEMA_VERSION); value.put("matchId", matchId); value.put("seq", seq);
            value.put("occurredAtUnixMs", longInteger(event.get("occurredAtUnixMs"), 1));
            value.put("round", integer(event.get("round"), 0)); value.put("phase", optionalText(event.get("phase"), 64));
            value.put("type", requiredText(event.get("type"), 64)); value.put("actorSide", actor);
            value.put("payload", event.get("payload")); result.add(value);
        }
        return result;
    }

    private List<Map<String, Object>> snapshots(JsonNode raw, String matchId, long lastSeq) {
        if (raw == null || !raw.isArray() || raw.size() > MAX_SNAPSHOTS) invalid();
        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> keys = new java.util.HashSet<>();
        for (JsonNode snapshot : raw) {
            if (!snapshot.isObject() || integer(snapshot.get("schemaVersion"), 1) != SCHEMA_VERSION
                    || !matchId.equals(text(snapshot.get("matchId")))) invalid();
            long after = longInteger(snapshot.get("afterSeq"), 0);
            if (after > lastSeq) invalid();
            String reason = requiredText(snapshot.get("reason"), 128);
            if (!keys.add(after + ":" + reason) || snapshot.get("state") == null || !snapshot.get("state").isObject()) invalid();
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("schemaVersion", SCHEMA_VERSION); value.put("matchId", matchId);
            value.put("round", integer(snapshot.get("round"), 0)); value.put("phase", optionalText(snapshot.get("phase"), 64));
            value.put("reason", reason); value.put("afterSeq", after);
            value.put("occurredAtUnixMs", longInteger(snapshot.get("occurredAtUnixMs"), 1));
            value.put("state", snapshot.get("state")); result.add(value);
        }
        return result;
    }

    private Long optionalTime(JsonNode value) { return value == null || value.isNull() || (value.isNumber() && value.longValue() == 0) ? null : longInteger(value, 1); }
    private int integer(JsonNode value, int minimum) { long number = longInteger(value, minimum); if (number > Integer.MAX_VALUE) invalid(); return (int) number; }
    private long longInteger(JsonNode value, long minimum) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < minimum) invalid();
        return value.longValue();
    }
    private String requiredText(JsonNode value, int max) { String text = text(value).trim(); if (text.isEmpty() || text.length() > max) invalid(); return text; }
    private String optionalText(JsonNode value, int max) { String text = text(value).trim(); return text.length() <= max ? text : text.substring(0, max); }
    private String text(JsonNode value) { return value == null || value.isNull() ? "" : value.asText(); }
    private void invalid() { throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_payload"); }

    record Normalized(String matchId, Map<String, Object> summary, List<Map<String, Object>> events,
                      List<Map<String, Object>> snapshots, Map<String, Object> payload) { }
}
