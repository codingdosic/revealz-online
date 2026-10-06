package com.revealz.backend.catalog;

import java.util.List;

import org.springframework.stereotype.Service;

@Service
class CatalogService {

    private final CatalogRepository repository;
    private final CatalogCache cache;

    CatalogService(CatalogRepository repository, CatalogCache cache) {
        this.repository = repository;
        this.cache = cache;
    }

    CatalogResponse loadPublicCatalog() {
        CatalogCache.Lookup lookup = cache.read();
        if (lookup.response() != null) {
            return lookup.response();
        }

        List<CatalogResponse.ProductResponse> products = repository.findEnabledProducts().stream()
                .map(product -> new CatalogResponse.ProductResponse(
                        product.productId(),
                        product.productType(),
                        product.displayName(),
                        product.description(),
                        product.priceGold(),
                        product.packSize(),
                        product.weightN(),
                        product.weightR(),
                        product.weightSr(),
                        product.weightUr(),
                        product.poolMode(),
                        "explicit".equals(product.poolMode()) ? product.pool() : List.of(),
                        product.accessoryType(),
                        product.accessoryId(),
                        product.sortOrder()))
                .toList();
        CatalogResponse response = new CatalogResponse(repository.findRevision(), products);
        if (lookup.cacheAvailable()) {
            cache.write(response);
        }
        return response;
    }
}
