package com.revealz.backend.shop;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.revealz.backend.web.ApiException;

@Service
class PurchaseService {
    private final PurchaseRepository repository;
    private final CardCatalog cards;
    private final SecureRandom random = new SecureRandom();

    PurchaseService(PurchaseRepository repository, CardCatalog cards) {
        this.repository = repository;
        this.cards = cards;
    }

    @Transactional
    Map<String, Object> purchase(String accountKey, Map<String, Object> body) {
        String productId = text(body.containsKey("product_id") ? body.get("product_id") : body.get("productId")).trim();
        if (productId.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "product_id_required");
        PurchaseRepository.Product product = repository.findEnabled(productId);
        if (product == null) throw new ApiException(HttpStatus.NOT_FOUND, "product_not_found");
        int count = Math.max(1, integer(body.containsKey("pack_count") ? body.get("pack_count") : body.get("packCount"), 1));
        return switch (product.type()) {
            case "pack" -> pack(accountKey, product, count);
            case "accessory" -> accessory(accountKey, product);
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "unsupported_product_type");
        };
    }

    private Map<String, Object> pack(String key, PurchaseRepository.Product product, int count) {
        Set<Integer> known = Set.copyOf(cards.nonTokenIds());
        List<Integer> pool = "all_non_token".equals(product.poolMode())
                ? cards.nonTokenIds() : product.pool().stream().filter(known::contains).toList();
        if (pool.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "empty_pool");
        int gold = spend(key, (long) Math.max(0, product.price()) * count);
        List<Integer> ids = new ArrayList<>();
        List<Integer> rarities = new ArrayList<>();
        int totalCards = Math.multiplyExact(product.packSize(), count);
        for (int index = 0; index < totalCards; index++) {
            int id = pool.get(random.nextInt(pool.size()));
            int rarity = roll(product.weights());
            repository.addCard(key, id, rarity);
            ids.add(id); rarities.add(rarity);
        }
        long revision = repository.bumpRevision(key);
        Map<String, Object> result = base(product, product.price() * count, count, gold, revision);
        result.put("granted_card_ids", ids);
        result.put("granted_rarities", rarities);
        result.put("owned", repository.ownedCards(key));
        return result;
    }

    private Map<String, Object> accessory(String key, PurchaseRepository.Product product) {
        int goldNow = lockedGold(key);
        if (repository.ownsAccessory(key, product.accessoryType(), product.accessoryId()))
            throw new ApiException(HttpStatus.CONFLICT, "already_owned");
        int price = Math.max(0, product.price());
        if (goldNow < price) throw new ApiException(HttpStatus.CONFLICT, "insufficient_gold");
        int gold = goldNow - price;
        repository.setGold(key, gold);
        repository.addAccessory(key, product.accessoryType(), product.accessoryId());
        long revision = repository.bumpRevision(key);
        Map<String, Object> result = base(product, price, 1, gold, revision);
        result.put("granted_accessory_type", product.accessoryType());
        result.put("granted_accessory_id", product.accessoryId());
        result.put("ownedAccessories", repository.accessories(key));
        return result;
    }

    private int spend(String key, long price) {
        int current = lockedGold(key);
        if (price > current) throw new ApiException(HttpStatus.CONFLICT, "insufficient_gold");
        int after = current - (int) price;
        repository.setGold(key, after);
        return after;
    }

    private int lockedGold(String key) {
        Integer gold = repository.lockGold(key);
        if (gold == null) throw new ApiException(HttpStatus.NOT_FOUND, "account_not_found");
        return gold;
    }

    private Map<String, Object> base(PurchaseRepository.Product product, int spent, int count, int gold, long revision) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true); result.put("product_id", product.id()); result.put("product_type", product.type());
        result.put("spent", spent); result.put("pack_count", count); result.put("gold", gold); result.put("metaRevision", revision);
        return result;
    }

    private int roll(List<Integer> weights) {
        int total = weights.stream().mapToInt(value -> Math.max(0, value)).sum();
        if (total <= 0) return 0;
        int value = random.nextInt(total);
        for (int tier = 0; tier < weights.size(); tier++) { value -= Math.max(0, weights.get(tier)); if (value < 0) return tier; }
        return 0;
    }
    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private static int integer(Object value, int fallback) {
        if (value instanceof Number number && Double.isFinite(number.doubleValue())) return (int) Math.floor(number.doubleValue());
        return fallback;
    }
}
