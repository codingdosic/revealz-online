package com.revealz.backend.shop;

import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import com.revealz.backend.auth.AccountOwnership;

@RestController
class PurchaseController {
    private final PurchaseService service;
    PurchaseController(PurchaseService service) { this.service = service; }

    @PostMapping("/v1/meta/accounts/{accountKey}/purchase")
    Map<String, Object> purchase(@PathVariable String accountKey, @RequestBody Map<String, Object> body, @AuthenticationPrincipal Jwt jwt) {
        AccountOwnership.require(jwt, accountKey);
        return service.purchase(accountKey, body);
    }
}
