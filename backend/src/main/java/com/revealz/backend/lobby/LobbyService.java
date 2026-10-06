package com.revealz.backend.lobby;

import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PreDestroy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import com.revealz.backend.web.ApiException;
import com.revealz.backend.worker.WorkerManager;
import com.revealz.backend.worker.WorkerProperties;

@Service
public class LobbyService {
    private final Object lock = new Object();
    private final WorkerManager workers;
    private final WorkerProperties properties;
    private final SecureRandom random = new SecureRandom();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final Map<String, Ticket> tickets = new LinkedHashMap<>();
    private final ArrayDeque<String> queue = new ArrayDeque<>();

    LobbyService(WorkerManager workers, WorkerProperties properties) {
        this.workers = workers;
        this.properties = properties;
    }

    Map<String, Object> createRoom(String accountKey) {
        WorkerManager.RoomView room = workers.acquire();
        return workers.join(room.roomCode(), accountKey).publicBody();
    }
    Map<String, Object> join(String code, String accountKey) { return workers.join(code, accountKey).publicBody(); }

    Map<String, Object> enqueue(String accountKey) {
        Ticket mine;
        Ticket first = null;
        synchronized (lock) {
            mine = new Ticket(ticketId(), accountKey);
            tickets.put(mine.id, mine); queue.addLast(mine.id);
            mine.timeout = scheduler.schedule(() -> expire(mine.id), properties.getMatchTimeoutMs(), TimeUnit.MILLISECONDS);
            if (queue.size() >= 2) {
                first = tickets.get(queue.removeFirst());
                Ticket second = tickets.get(queue.removeFirst());
                if (first != null && second != null && first.status == Status.QUEUED && second.status == Status.QUEUED) {
                    first.pairing = true; second.pairing = true;
                } else first = null;
            }
        }
        if (first != null) pair(first, mine);
        synchronized (lock) {
            if (mine.status == Status.ERROR) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, publicTicket(mine));
            return publicTicket(mine);
        }
    }

    Map<String, Object> cancel(String ticketId, String accountKey) {
        synchronized (lock) {
            Ticket ticket = tickets.get(ticketId == null ? "" : ticketId.trim());
            if (ticket == null) throw new ApiException(HttpStatus.NOT_FOUND, "ticket_not_found");
            if (!ticket.accountKey.equals(accountKey)) throw new ApiException(HttpStatus.FORBIDDEN, "ticket_forbidden");
            if (ticket.status == Status.MATCHED) throw new ApiException(HttpStatus.CONFLICT, Map.of("error", "already_matched", "ticketId", ticket.id));
            if (ticket.pairing) throw new ApiException(HttpStatus.CONFLICT, Map.of("error", "pairing_in_progress", "ticketId", ticket.id));
            if (ticket.status == Status.QUEUED) {
                queue.remove(ticket.id); ticket.status = Status.CANCELLED; ticket.timeout.cancel(false);
            }
            return Map.of("ok", true, "ticketId", ticket.id, "status", text(ticket.status), "queueSize", queue.size());
        }
    }

    Map<String, Object> ticket(String id, String accountKey) {
        synchronized (lock) {
            Ticket ticket = tickets.get(id);
            if (ticket == null) throw new ApiException(HttpStatus.NOT_FOUND, "ticket_not_found");
            if (!ticket.accountKey.equals(accountKey)) throw new ApiException(HttpStatus.FORBIDDEN, "ticket_forbidden");
            return publicTicket(ticket);
        }
    }

    public int queueSize() { synchronized (lock) { return queue.size(); } }

    private void pair(Ticket first, Ticket second) {
        WorkerManager.RoomView room;
        try {
            room = workers.acquire();
            var participants = workers.reserveBothSeats(room.roomCode(), first.accountKey, second.accountKey);
            first.participant = participants.get(0); second.participant = participants.get(1);
        } catch (RuntimeException exception) {
            synchronized (lock) {
                first.pairing = false; second.pairing = false;
                first.status = Status.ERROR; second.status = Status.ERROR;
                first.error = "worker_not_ready"; second.error = "worker_not_ready";
            }
            return;
        }
        synchronized (lock) {
            first.pairing = false; second.pairing = false;
            if (first.status != Status.QUEUED || second.status != Status.QUEUED) return;
            first.timeout.cancel(false); second.timeout.cancel(false);
            first.status = Status.MATCHED; second.status = Status.MATCHED;
            first.match = room; second.match = room;
        }
    }

    private void expire(String id) {
        synchronized (lock) {
            Ticket ticket = tickets.get(id);
            if (ticket == null || ticket.status != Status.QUEUED || ticket.pairing) return;
            queue.remove(id); ticket.status = Status.EXPIRED;
        }
    }

    private Map<String, Object> publicTicket(Ticket ticket) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", text(ticket.status)); result.put("ticketId", ticket.id);
        result.put("queueSize", queue.size()); result.put("matchTimeoutMs", properties.getMatchTimeoutMs());
        if (ticket.participant != null && ticket.status == Status.MATCHED) result.putAll(ticket.participant.publicBody());
        if (ticket.error != null && ticket.status == Status.ERROR) result.put("error", ticket.error);
        return result;
    }

    private String ticketId() { return HexFormat.of().formatHex(random.generateSeed(8)); }
    private String text(Status value) { return value.name().toLowerCase(); }

    @PreDestroy void shutdown() { scheduler.shutdownNow(); }

    private enum Status { QUEUED, MATCHED, CANCELLED, EXPIRED, ERROR }
    private static final class Ticket {
        final String id; Status status = Status.QUEUED; boolean pairing; String error;
        WorkerManager.RoomView match; WorkerManager.ParticipantView participant; java.util.concurrent.ScheduledFuture<?> timeout;
        final String accountKey;
        Ticket(String id, String accountKey) { this.id = id; this.accountKey = accountKey; }
    }
}
