package com.revealz.backend.worker;

import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import com.revealz.backend.meta.MetaService;
import com.revealz.backend.web.ApiException;

@RestController
@RequestMapping("/v1/internal/matches/{matchId}")
class InternalMatchController {
    private final WorkerManager workers;
    private final MetaService meta;
    InternalMatchController(WorkerManager workers, MetaService meta) { this.workers = workers; this.meta = meta; }

    @PostMapping("/admissions/consume")
    Map<String, Object> consume(@PathVariable UUID matchId, @RequestHeader(value="Authorization", required=false) String auth,
            @RequestBody Map<String, Object> body) {
        return workers.consumeAdmission(matchId, bearer(auth), String.valueOf(body.getOrDefault("admissionTicket", "")));
    }

    @GetMapping("/accounts/{accountKey}")
    Map<String, Object> account(@PathVariable UUID matchId, @PathVariable String accountKey,
            @RequestHeader(value="Authorization", required=false) String auth) {
        require(matchId, accountKey, auth); return meta.find(accountKey);
    }

    @PostMapping("/accounts/{accountKey}/validate-deck")
    Map<String, Object> validate(@PathVariable UUID matchId, @PathVariable String accountKey,
            @RequestHeader(value="Authorization", required=false) String auth, @RequestBody Map<String, Object> body) {
        require(matchId, accountKey, auth); return meta.validateDeck(accountKey, body);
    }

    private void require(UUID matchId, String accountKey, String auth) {
        if (!workers.authenticate(matchId, bearer(auth)) || !workers.isParticipant(matchId, accountKey))
            throw new ApiException(HttpStatus.UNAUTHORIZED, "worker_scope_invalid");
    }
    private String bearer(String value) { return value != null && value.startsWith("Bearer ") ? value.substring(7).trim() : ""; }
}
