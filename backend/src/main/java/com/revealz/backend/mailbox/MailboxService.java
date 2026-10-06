package com.revealz.backend.mailbox;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.revealz.backend.meta.MetaService;
import com.revealz.backend.web.ApiException;

@Service
@Transactional(readOnly = true)
public class MailboxService {
    private final MailboxRepository repository;
    private final MetaService metaService;

    MailboxService(MailboxRepository repository, MetaService metaService) {
        this.repository = repository;
        this.metaService = metaService;
    }

    Map<String, Object> list(String accountKey) {
        String key = requiredKey(accountKey);
        if (!repository.accountExists(key, false)) throw new ApiException(HttpStatus.NOT_FOUND, "account_not_found");
        List<Map<String, Object>> items = repository.pending(key, false);
        return Map.of("ok", true, "items", items, "pendingCount", items.size());
    }

    @Transactional
    Map<String, Object> claim(String accountKey, Object rawId) {
        String key = requiredKey(accountKey);
        long id = mailboxId(rawId);
        Map<String, Object> item = repository.lockItem(key, id);
        if (item == null) throw new ApiException(HttpStatus.NOT_FOUND, "mailbox_item_not_found");
        if (!"pending".equals(item.get("status"))) throw new ApiException(HttpStatus.CONFLICT, "already_claimed");
        repository.apply(key, payload(item));
        Map<String, Object> claimed = repository.markClaimed(key, id);
        if (claimed == null) throw new ApiException(HttpStatus.CONFLICT, "already_claimed");
        repository.bumpRevision(key);
        return Map.of("ok", true, "claimed", claimed, "snapshot", metaService.find(key));
    }

    @Transactional
    Map<String, Object> claimAll(String accountKey) {
        String key = requiredKey(accountKey);
        if (!repository.accountExists(key, true)) throw new ApiException(HttpStatus.NOT_FOUND, "account_not_found");
        List<Map<String, Object>> pending = repository.pending(key, true);
        for (Map<String, Object> item : pending) repository.apply(key, payload(item));
        if (!pending.isEmpty()) {
            repository.markAllClaimed(key);
            repository.bumpRevision(key);
        }
        return Map.of("ok", true, "claimedCount", pending.size(), "snapshot", metaService.find(key));
    }

    @Transactional
    public Map<String, Object> enqueue(String accountKey, String source, String title, Map<String, Object> rawPayload) {
        String key = requiredKey(accountKey);
        if (!repository.accountExists(key, false)) throw new ApiException(HttpStatus.NOT_FOUND, "account_not_found");
        return repository.enqueue(key, limited(source, 32, "ops"), limited(title, 120, "선물"), normalize(rawPayload));
    }

    private Map<String, Object> normalize(Map<String, Object> raw) {
        int gold = integer(raw.get("gold"), 0);
        List<?> cards = raw.get("cards") instanceof List<?> list ? list : List.of();
        if ((gold > 0) == !cards.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "payload_gold_xor_cards");
        if (gold > 0) return Map.of("gold", gold);
        return Map.of("cards", cards);
    }

    @SuppressWarnings("unchecked") private Map<String, Object> payload(Map<String, Object> item) {
        Object value = item.get("payload");
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : new LinkedHashMap<>();
    }
    private String requiredKey(String value) {
        String key = value == null ? "" : value.trim();
        if (key.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "account_key_required");
        return key;
    }
    private long mailboxId(Object value) {
        String text = String.valueOf(value == null ? "" : value).trim();
        if (!text.matches("\\d+")) throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_mailbox_id");
        try { return Long.parseLong(text); } catch (NumberFormatException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_mailbox_id");
        }
    }
    private int integer(Object value, int fallback) {
        return value instanceof Number number ? (int) Math.floor(number.doubleValue()) : fallback;
    }
    private String limited(String value, int max, String fallback) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) text = fallback;
        return text.length() <= max ? text : text.substring(0, max);
    }
}
