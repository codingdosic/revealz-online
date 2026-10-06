package com.revealz.backend.account;

import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import com.revealz.backend.auth.AccountOwnership;

@RestController
@RequestMapping("/v1/local/accounts")
@Profile("local-account")
class AccountController {

    private final AccountService service;

    AccountController(AccountService service) {
        this.service = service;
    }

    @GetMapping("/{accountKey}")
    AccountSummaryResponse find(@PathVariable String accountKey, @AuthenticationPrincipal Jwt jwt) {
        AccountOwnership.require(jwt, accountKey);
        return service.find(accountKey);
    }

    @PatchMapping("/{accountKey}")
    AccountSummaryResponse changeDisplayName(
            @PathVariable String accountKey,
            @Valid @RequestBody UpdateAccountRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        AccountOwnership.require(jwt, accountKey);
        return service.changeDisplayName(accountKey, request);
    }
}
