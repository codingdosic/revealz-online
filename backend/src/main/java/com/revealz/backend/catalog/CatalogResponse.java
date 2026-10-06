package com.revealz.backend.catalog;

import java.math.BigDecimal;
import java.util.List;

public record CatalogResponse(BigDecimal revision, List<ProductResponse> products) {

    public record ProductResponse(
            String productId,
            String productType,
            String displayName,
            String description,
            int priceGold,
            int packSize,
            int weightN,
            int weightR,
            int weightSr,
            int weightUr,
            String poolMode,
            List<Long> pool,
            String accessoryType,
            String accessoryId,
            int sortOrder) {
    }
}
