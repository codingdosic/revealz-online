package com.revealz.backend.mailbox;

import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import com.revealz.backend.auth.AccountOwnership;

@RestController
@RequestMapping("/v1/meta/accounts/{accountKey}/mailbox")
class MailboxController {
    private final MailboxService service;
    MailboxController(MailboxService service) { this.service = service; }

    @GetMapping Map<String, Object> list(@PathVariable String accountKey, @AuthenticationPrincipal Jwt jwt) {
        AccountOwnership.require(jwt, accountKey); return service.list(accountKey);
    }
    @PostMapping("/claim") Map<String, Object> claim(@PathVariable String accountKey, @RequestBody Map<String, Object> body, @AuthenticationPrincipal Jwt jwt) {
        AccountOwnership.require(jwt, accountKey);
        Object id = body.containsKey("id") ? body.get("id") : body.containsKey("itemId") ? body.get("itemId") : body.get("item_id");
        return service.claim(accountKey, id);
    }
    @PostMapping("/claim-all") Map<String, Object> claimAll(@PathVariable String accountKey, @AuthenticationPrincipal Jwt jwt) {
        AccountOwnership.require(jwt, accountKey); return service.claimAll(accountKey);
    }
}
