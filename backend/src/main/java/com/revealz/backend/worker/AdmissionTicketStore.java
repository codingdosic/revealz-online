package com.revealz.backend.worker;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

class AdmissionTicketStore {
    private final Map<String, Admission> tickets = new LinkedHashMap<>();
    private final Map<UUID, Map<String, Integer>> participants = new LinkedHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;
    AdmissionTicketStore() { this(Clock.systemUTC()); }
    AdmissionTicketStore(Clock clock) { this.clock = clock; }

    synchronized Issued issue(UUID matchId, String accountKey, int seat, long lifetimeSeconds) {
        String token = randomToken();
        tickets.put(hash(token), new Admission(matchId, accountKey, seat, clock.instant().plusSeconds(lifetimeSeconds)));
        participants.computeIfAbsent(matchId, ignored -> new LinkedHashMap<>()).put(accountKey, seat);
        return new Issued(token, seat);
    }

    synchronized Result consume(UUID matchId, String token) {
        Admission admission = tickets.get(hash(token));
        if (admission == null || !admission.matchId().equals(matchId) || !admission.expiresAt().isAfter(clock.instant()))
            return null;
        tickets.remove(hash(token));
        return new Result(admission.accountKey(), admission.seat());
    }

    synchronized boolean isParticipant(UUID matchId, String accountKey) {
        return participants.getOrDefault(matchId, Map.of()).containsKey(accountKey);
    }
    synchronized void removeMatch(UUID matchId) { participants.remove(matchId); }

    private String randomToken() { byte[] bytes = new byte[32]; random.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    record Issued(String token, int seat) { }
    record Result(String accountKey, int seat) { }
    private record Admission(UUID matchId, String accountKey, int seat, Instant expiresAt) { }
}
