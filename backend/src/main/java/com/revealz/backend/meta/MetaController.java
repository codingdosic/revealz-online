package com.revealz.backend.meta;

import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import com.revealz.backend.auth.AccountOwnership;

@RestController
@RequestMapping("/v1/meta/accounts/{accountKey}")
class MetaController {
    private final MetaService service;
    MetaController(MetaService service) { this.service = service; }

    @GetMapping Map<String, Object> find(@PathVariable String accountKey, @AuthenticationPrincipal Jwt jwt) {
        AccountOwnership.require(jwt, accountKey); return service.find(accountKey);
    }
    @PutMapping Map<String, Object> put(@PathVariable String accountKey, @RequestBody Map<String, Object> body, @AuthenticationPrincipal Jwt jwt) {
        AccountOwnership.require(jwt, accountKey); return service.upsert(accountKey, body);
    }
    @PostMapping Map<String, Object> post(@PathVariable String accountKey, @RequestBody Map<String, Object> body, @AuthenticationPrincipal Jwt jwt) {
        AccountOwnership.require(jwt, accountKey); return service.upsert(accountKey, body);
    }
    @PostMapping("/profile") Map<String, Object> profile(@PathVariable String accountKey, @RequestBody Map<String, Object> body, @AuthenticationPrincipal Jwt jwt) {
        AccountOwnership.require(jwt, accountKey); return service.updateProfile(accountKey, body);
    }
    @PostMapping("/validate-deck") Map<String, Object> validate(@PathVariable String accountKey, @RequestBody Map<String, Object> body, @AuthenticationPrincipal Jwt jwt) {
        AccountOwnership.require(jwt, accountKey); return service.validateDeck(accountKey, body);
    }
}
