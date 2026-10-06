package com.revealz.backend.lobby;

import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import com.revealz.backend.auth.AccountOwnership;

@RestController
class LobbyController {
    private final LobbyService service;
    LobbyController(LobbyService service) { this.service = service; }

    @PostMapping("/v1/rooms") @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    Map<String, Object> create(@RequestBody(required = false) Map<String, Object> ignored, @AuthenticationPrincipal Jwt jwt) { return service.createRoom(AccountOwnership.subject(jwt)); }
    @PostMapping("/v1/rooms/{code}/join")
    Map<String, Object> join(@PathVariable String code, @RequestBody(required = false) Map<String, Object> ignored, @AuthenticationPrincipal Jwt jwt) { return service.join(code, AccountOwnership.subject(jwt)); }
    @PostMapping("/v1/matchmaking/enqueue")
    Map<String, Object> enqueue(@RequestBody(required = false) Map<String, Object> ignored, @AuthenticationPrincipal Jwt jwt) { return service.enqueue(AccountOwnership.subject(jwt)); }
    @PostMapping("/v1/matchmaking/cancel")
    Map<String, Object> cancel(@RequestBody Map<String, Object> body, @AuthenticationPrincipal Jwt jwt) { return service.cancel(String.valueOf(body.getOrDefault("ticketId", "")), AccountOwnership.subject(jwt)); }
    @GetMapping("/v1/matchmaking/tickets/{ticketId}")
    Map<String, Object> ticket(@PathVariable String ticketId, @AuthenticationPrincipal Jwt jwt) { return service.ticket(ticketId, AccountOwnership.subject(jwt)); }
}
