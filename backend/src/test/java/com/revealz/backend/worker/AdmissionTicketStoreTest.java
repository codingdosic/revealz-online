package com.revealz.backend.worker;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AdmissionTicketStoreTest {
    @Test void rejectsWrongMatchReuseAndExpiry() {
        MutableClock clock = new MutableClock();
        AdmissionTicketStore store = new AdmissionTicketStore(clock);
        UUID match = UUID.randomUUID();
        var first = store.issue(match, "account-a", 0, 60);
        assertNull(store.consume(UUID.randomUUID(), first.token()));
        var accepted = store.consume(match, first.token());
        assertEquals("account-a", accepted.accountKey());
        assertEquals(0, accepted.seat());
        assertNull(store.consume(match, first.token()));
        var expiring = store.issue(match, "account-b", 1, 60);
        clock.advanceSeconds(61);
        assertNull(store.consume(match, expiring.token()));
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-03T00:00:00Z");
        void advanceSeconds(long seconds) { now = now.plusSeconds(seconds); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
