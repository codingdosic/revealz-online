package com.revealz.backend.worker;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import com.revealz.backend.web.ApiException;

@Service
public class WorkerManager {
    private static final String READY_MARKER = "[MP-SERVER] listening";
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private final Object stateLock = new Object();
    private final WorkerProperties properties;
    private final SecureRandom random = new SecureRandom();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ArrayDeque<Integer> freePorts = new ArrayDeque<>();
    private final Map<String, Room> rooms = new LinkedHashMap<>();
    private final ArrayDeque<Room> warmReady = new ArrayDeque<>();
    private final Map<UUID, Credential> credentials = new LinkedHashMap<>();
    private final AdmissionTicketStore admissionTickets = new AdmissionTicketStore();
    private Map<String, Object> fingerprint;
    private volatile boolean shuttingDown;

    WorkerManager(WorkerProperties properties) {
        this.properties = properties;
        for (int port = properties.getPortStart(); port <= properties.getPortEnd(); port++) freePorts.add(port);
    }

    @PostConstruct
    void start() { replenishWarmPool(); }

    public RoomView acquire() {
        Room room = claimWarm();
        if (room == null) room = reserve(false);
        awaitReady(room);
        scheduleTtl(room);
        replenishWarmPool();
        return room.view();
    }

    public ParticipantView join(String rawCode, String accountKey) {
        String code = rawCode == null ? "" : rawCode.trim().toUpperCase();
        synchronized (stateLock) {
            Room room = rooms.get(code);
            if (room == null || room.warm || room.closed.get()) throw new ApiException(HttpStatus.NOT_FOUND, "room_not_found");
            if (room.seats >= 2) throw new ApiException(HttpStatus.CONFLICT, "room_full");
            int seat = room.seats++;
            if (room.ttl != null) room.ttl.cancel(false);
            return participant(room, accountKey, seat);
        }
    }

    public List<ParticipantView> reserveBothSeats(String roomCode, String firstAccount, String secondAccount) {
        synchronized (stateLock) {
            Room room = rooms.get(roomCode);
            if (room != null) {
                room.seats = 2;
                if (room.ttl != null) room.ttl.cancel(false);
                return List.of(participant(room, firstAccount, 0), participant(room, secondAccount, 1));
            }
            throw new ApiException(HttpStatus.NOT_FOUND, "room_not_found");
        }
    }

    public Map<String, Object> consumeAdmission(UUID matchId, String workerToken, String rawTicket) {
        if (!authenticate(matchId, workerToken)) throw new ApiException(HttpStatus.UNAUTHORIZED, "worker_unauthorized");
        AdmissionTicketStore.Result admission = admissionTickets.consume(matchId, rawTicket);
        if (admission == null) throw new ApiException(HttpStatus.UNAUTHORIZED, "admission_invalid");
        return Map.of("accountKey", admission.accountKey(), "seat", admission.seat());
    }

    public boolean isParticipant(UUID matchId, String accountKey) {
        return admissionTickets.isParticipant(matchId, accountKey);
    }

    public boolean authenticate(UUID matchId, String token) {
        synchronized (stateLock) {
            Credential credential = credentials.get(matchId);
            if (credential == null || !credential.claimed) return false;
            if (credential.expiresAt != null && credential.expiresAt.isBefore(Instant.now())) return false;
            return MessageDigest.isEqual(credential.digest, digest(token));
        }
    }

    public Map<String, Object> status() {
        synchronized (stateLock) {
            long warmSlots = rooms.values().stream().filter(room -> room.warm).count();
            return Map.of("rooms", rooms.size(), "freePorts", freePorts.size(),
                    "warmReady", warmReady.size(), "warmSpawning", Math.max(0, warmSlots - warmReady.size()),
                    "warmTarget", properties.getWarmPoolSize());
        }
    }

    public synchronized Map<String, Object> fingerprint() {
        if (fingerprint != null) return fingerprint;
        Path path = workerPath();
        if (path == null || !Files.isRegularFile(path))
            return Map.of("present", false, "path", path == null ? "" : path.toString(), "sha256", "", "bytes", 0, "mtimeUtc", "");
        try {
            fingerprint = Map.of("present", true, "path", path.toString(), "sha256", fileHash(path),
                    "bytes", Files.size(path), "mtimeUtc", Files.getLastModifiedTime(path).toInstant().toString());
            return fingerprint;
        } catch (IOException exception) {
            return Map.of("present", false, "path", path.toString(), "sha256", "", "bytes", 0, "mtimeUtc", "");
        }
    }

    public WorkerProperties properties() { return properties; }

    private Room claimWarm() {
        synchronized (stateLock) {
            while (!warmReady.isEmpty()) {
                Room room = warmReady.removeFirst();
                if (!room.closed.get() && room.warm && room.ready.isDone() && !room.ready.isCompletedExceptionally()) {
                    room.warm = false;
                    Credential credential = credentials.get(room.matchId);
                    if (credential != null) credential.claimed = true;
                    return room;
                }
            }
            return null;
        }
    }

    private Room reserve(boolean warm) {
        Room room;
        String token;
        synchronized (stateLock) {
            Integer port = freePorts.pollFirst();
            if (port == null) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "no_free_ports");
            String code = roomCode();
            UUID matchId = UUID.randomUUID();
            token = Base64.getUrlEncoder().withoutPadding().encodeToString(random.generateSeed(32));
            room = new Room(code, port, matchId, warm);
            rooms.put(code, room);
            credentials.put(matchId, new Credential(digest(token), !warm));
        }
        try {
            startProcess(room, token);
            return room;
        } catch (RuntimeException exception) {
            cleanup(room, "spawn_failed");
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "spawn_failed");
        }
    }

    private void startProcess(Room room, String token) {
        Path bin = workerPath();
        if (bin == null || !Files.isRegularFile(bin)) throw new IllegalStateException("worker_missing");
        List<String> command = new ArrayList<>();
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            Path stdbuf = Path.of("/usr/bin/stdbuf");
            if (Files.isExecutable(stdbuf)) {
                command.add(stdbuf.toString()); command.add("-oL"); command.add("-eL");
            }
            command.add(bin.toString()); command.add("--headless"); command.add("--");
        } else {
            command.add(bin.toString()); command.add("--");
        }
        command.add("--port"); command.add(String.valueOf(room.port));
        command.add("--room-code"); command.add(room.code);
        ProcessBuilder builder = new ProcessBuilder(command).directory(bin.toAbsolutePath().getParent().toFile());
        builder.environment().put("GODOT_SILENCE_ROOT_WARNING", "1");
        builder.environment().put("META_LOBBY_URL", properties.getMetaLobbyUrl());
        builder.environment().put("MATCH_LOG_ID", room.matchId.toString());
        builder.environment().put("MATCH_LOG_TOKEN", token);
        try {
            room.process = builder.start();
        } catch (IOException exception) {
            throw new IllegalStateException("worker_start_failed", exception);
        }
        executor.submit(() -> consume(room, room.process.getInputStream(), false));
        executor.submit(() -> consume(room, room.process.getErrorStream(), true));
        room.process.onExit().thenRun(() -> cleanup(room, "exit"));
        room.readyTimeout = scheduler.schedule(() -> {
            room.ready.completeExceptionally(new IllegalStateException("worker_not_ready"));
            cleanup(room, "ready_timeout");
        }, properties.getWorkerReadyMs(), TimeUnit.MILLISECONDS);
    }

    private void consume(Room room, InputStream input, boolean error) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (error) System.err.println("[worker " + room.code + "] " + line);
                else System.out.println("[worker " + room.code + "] " + line);
                if (line.contains(READY_MARKER) && room.ready.complete(null)) {
                    if (room.readyTimeout != null) room.readyTimeout.cancel(false);
                    if (room.warm) synchronized (stateLock) { if (!room.closed.get()) warmReady.add(room); }
                }
            }
        } catch (IOException ignored) { }
    }

    private void awaitReady(Room room) {
        try {
            room.ready.get(properties.getWorkerReadyMs() + 1_000, TimeUnit.MILLISECONDS);
        } catch (Exception exception) {
            cleanup(room, "ready_failed");
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "worker_not_ready");
        }
    }

    private void scheduleTtl(Room room) {
        synchronized (stateLock) {
            if (room.ttl != null) room.ttl.cancel(false);
            room.ttl = scheduler.schedule(() -> {
                synchronized (stateLock) { if (room.seats > 0) return; }
                cleanup(room, "ttl");
            }, properties.getRoomTtlMs(), TimeUnit.MILLISECONDS);
        }
    }

    private void cleanup(Room room, String reason) {
        if (!room.closed.compareAndSet(false, true)) return;
        synchronized (stateLock) {
            rooms.remove(room.code, room); warmReady.remove(room); freePorts.add(room.port);
            List<Integer> sorted = freePorts.stream().sorted().toList(); freePorts.clear(); freePorts.addAll(sorted);
            Credential credential = credentials.get(room.matchId);
            if (credential != null && credential.claimed) {
                credential.expiresAt = Instant.now().plusMillis(properties.getCredentialGraceMs());
                scheduler.schedule(() -> { synchronized (stateLock) {
                    credentials.remove(room.matchId, credential);
                    admissionTickets.removeMatch(room.matchId);
                } },
                        properties.getCredentialGraceMs(), TimeUnit.MILLISECONDS);
            } else credentials.remove(room.matchId);
            if (credential == null || !credential.claimed) admissionTickets.removeMatch(room.matchId);
        }
        if (room.ttl != null) room.ttl.cancel(false);
        if (room.readyTimeout != null) room.readyTimeout.cancel(false);
        room.ready.completeExceptionally(new IllegalStateException(reason));
        if (room.process != null && room.process.isAlive()) room.process.destroy();
        if (!shuttingDown) replenishWarmPool();
    }

    private void replenishWarmPool() {
        if (shuttingDown) return;
        int missing;
        synchronized (stateLock) {
            long slots = rooms.values().stream().filter(room -> room.warm).count();
            missing = properties.getWarmPoolSize() - (int) slots;
        }
        for (int index = 0; index < missing; index++) executor.submit(() -> {
            try { reserve(true); } catch (RuntimeException ignored) { }
        });
    }

    private String roomCode() {
        for (int attempt = 0; attempt < 64; attempt++) {
            StringBuilder code = new StringBuilder(4);
            for (int index = 0; index < 4; index++) code.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
            if (!rooms.containsKey(code.toString())) return code.toString();
        }
        throw new IllegalStateException("room_code_unavailable");
    }

    private Path workerPath() {
        String value = properties.getWorkerBin();
        return value == null || value.isBlank() ? null : Path.of(value).toAbsolutePath().normalize();
    }
    private byte[] digest(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private ParticipantView participant(Room room, String accountKey, int seat) {
        AdmissionTicketStore.Issued admission = admissionTickets.issue(
                room.matchId, accountKey, seat, properties.getAdmissionSeconds());
        return new ParticipantView(room.view(), admission.token(), admission.seat());
    }
    private String fileHash(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (DigestInputStream input = new DigestInputStream(Files.newInputStream(path), digest)) {
                input.transferTo(OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    @PreDestroy
    void shutdown() {
        shuttingDown = true;
        List<Room> active;
        synchronized (stateLock) { active = List.copyOf(rooms.values()); }
        active.forEach(room -> cleanup(room, "shutdown"));
        scheduler.shutdownNow(); executor.shutdownNow();
    }

    public record RoomView(String roomCode, String host, int port, UUID matchId) {
        public Map<String, Object> publicBody() { return Map.of("roomCode", roomCode, "host", host, "port", port); }
    }
    public record ParticipantView(RoomView room, String admissionTicket, int seat) {
        public Map<String, Object> publicBody() {
            Map<String, Object> result = new LinkedHashMap<>(room.publicBody());
            result.put("admissionTicket", admissionTicket); result.put("seat", seat);
            return result;
        }
    }
    private final class Room {
        final String code; final int port; final UUID matchId; final CompletableFuture<Void> ready = new CompletableFuture<>();
        final AtomicBoolean closed = new AtomicBoolean(); volatile boolean warm; volatile int seats; volatile Process process;
        volatile java.util.concurrent.ScheduledFuture<?> ttl; volatile java.util.concurrent.ScheduledFuture<?> readyTimeout;
        Room(String code, int port, UUID matchId, boolean warm) { this.code = code; this.port = port; this.matchId = matchId; this.warm = warm; }
        RoomView view() { return new RoomView(code, properties.getPublicHost(), port, matchId); }
    }
    private static final class Credential {
        final byte[] digest; boolean claimed; Instant expiresAt;
        Credential(byte[] digest, boolean claimed) { this.digest = digest; this.claimed = claimed; }
    }
}
