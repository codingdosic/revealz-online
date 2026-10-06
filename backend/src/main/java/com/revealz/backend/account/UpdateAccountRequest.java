package com.revealz.backend.account;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record UpdateAccountRequest(
        @NotBlank
        @Size(max = 50)
        @Pattern(regexp = "[A-Za-z0-9가-힣_-]+")
        String displayName,

        @NotNull
        @PositiveOrZero
        Long baseRevision
) {

    public UpdateAccountRequest {
        if (displayName != null) {
            displayName = displayName.trim();
        }
    }
}
