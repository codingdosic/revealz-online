package com.revealz.backend.ops;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.revealz.backend.mailbox.MailboxService;
import com.revealz.backend.meta.MetaService;
import com.revealz.backend.patchnote.PatchNoteService;
import com.revealz.backend.shop.CardCatalog;
import com.revealz.backend.web.ApiException;
import com.revealz.backend.account.Account;
import com.revealz.backend.account.AccountRepository;
import com.revealz.backend.account.DeletedAccount;
import com.revealz.backend.account.DeletedAccountRepository;

@Service
class OpsService {
    private static final int MAX_GRANT = 99;
    private static final int MAX_GOLD = 999_999_999;
    private final OpsRepository repository;
    private final MetaService meta;
    private final MailboxService mailbox;
    private final CardCatalog cards;
    private final PatchNoteService patchNotes;
    private final MaintenanceStore maintenance;
    private final OpsProcessService processes;
    private final OpsMonitorService monitor;
    private final AccountRepository accounts;
    private final DeletedAccountRepository deletedAccounts;

    OpsService(OpsRepository repository, MetaService meta, MailboxService mailbox, CardCatalog cards,
            PatchNoteService patchNotes, MaintenanceStore maintenance,
            OpsProcessService processes, OpsMonitorService monitor,
            AccountRepository accounts, DeletedAccountRepository deletedAccounts) {
        this.repository = repository;
        this.meta = meta;
        this.mailbox = mailbox;
        this.cards = cards;
        this.patchNotes = patchNotes;
        this.maintenance = maintenance;
        this.processes = processes;
        this.monitor = monitor;
        this.accounts = accounts;
        this.deletedAccounts = deletedAccounts;
    }

    Map<String, Object> accounts(String query, String rawSort, String rawOrder, int rawPage, int rawLimit) {
        String q = limited(query == null ? "" : query.trim(), 120);
        String sort = "name".equalsIgnoreCase(rawSort) ? "name" : "created";
        String order = "asc".equalsIgnoreCase(rawOrder) ? "asc" : "desc";
        return repository.listAccounts(q, sort, order, Math.max(1, rawPage), Math.max(1, Math.min(50, rawLimit)));
    }

    Map<String, Object> account(String key, String displayName) {
        if (key != null && !key.isBlank()) return Map.of("ok", true, "snapshot", meta.find(key.trim()));
        if (displayName != null && !displayName.isBlank()) {
            Map<String, Object> listed = accounts(displayName, "name", "asc", 1, 20);
            @SuppressWarnings("unchecked") List<Map<String, Object>> rows = (List<Map<String, Object>>) listed.get("accounts");
            List<Map<String, Object>> results = rows.stream().map(row -> Map.<String, Object>of("account", Map.of(
                    "accountKey", row.get("accountKey"), "displayName", row.get("displayName")))).toList();
            return Map.of("ok", true, "results", results);
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, "key_or_displayName_required");
    }

    @Transactional
    Map<String, Object> patchAccount(Map<String, Object> body) {
        String key = accountKey(body);
        Account account = accounts.findByAccountKey(key)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "account_not_found"));
        Object displayName = body.containsKey("displayName") ? body.get("displayName") : body.get("display_name");
        Object rawGold = body.get("gold");
        if (displayName == null && rawGold == null) throw new ApiException(HttpStatus.BAD_REQUEST, "nothing_to_update");
        Map<String, Object> updates = new LinkedHashMap<>();
        if (displayName != null) updates.put("displayName", limited(String.valueOf(displayName), 64));
        if (rawGold != null && !String.valueOf(rawGold).isBlank()) updates.put("gold", boundedInteger(rawGold, 0, MAX_GOLD, "invalid_gold"));
        if (updates.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "nothing_to_update");
        if (updates.containsKey("displayName")) account.updateIdentity(account.authKind(), String.valueOf(updates.get("displayName")));
        repository.setGold(key, updates.get("gold"));
        account.bumpRevision();
        accounts.flush();
        return Map.of("ok", true, "updated", updates, "snapshot", meta.find(key));
    }

    @Transactional
    Map<String, Object> deleteAccount(Map<String, Object> body) {
        String key = accountKey(body);
        Account account = accounts.findByAccountKey(key)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "account_not_found"));
        deletedAccounts.save(new DeletedAccount(key));
        accounts.delete(account);
        accounts.flush();
        return Map.of("ok", true, "deletedAccountKey", key);
    }

    @Transactional
    Map<String, Object> grant(Map<String, Object> body) {
        String key = accountKey(body);
        if (!accounts.existsById(key)) throw new ApiException(HttpStatus.NOT_FOUND, "account_not_found");
        String mode = String.valueOf(body.getOrDefault("mode", "one"));
        int count = boundedInteger(body.getOrDefault("count", 1), 1, MAX_GRANT, "invalid_count");
        if ("gold".equals(mode)) return grantGold(key, body.getOrDefault("gold", count));
        if ("all".equals(mode)) return grantAll(key, count);
        int cardId = boundedInteger(body.containsKey("cardId") ? body.get("cardId") : body.get("card_id"), 1, Integer.MAX_VALUE, "invalid_card_id");
        int rarity = boundedInteger(body.getOrDefault("rarity", 0), 0, 3, "invalid_rarity");
        Map<String, Object> item = enqueueCard(key, cardId, rarity, count);
        return Map.of("ok", true, "granted", Map.of("mode", "one", "cardId", cardId, "rarity", rarity, "count", count),
                "enqueued", item, "snapshot", meta.find(key));
    }

    private Map<String, Object> grantGold(String key, Object rawGold) {
        int gold = boundedInteger(rawGold, 1, MAX_GOLD, "invalid_gold");
        Map<String, Object> item = mailbox.enqueue(key, "ops", "골드", Map.of("gold", gold));
        return Map.of("ok", true, "granted", Map.of("mode", "gold", "gold", gold),
                "enqueued", item, "snapshot", meta.find(key));
    }

    private Map<String, Object> grantAll(String key, int count) {
        List<Integer> ids = cards.nonTokenIds();
        if (ids.isEmpty()) throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "catalog_empty");
        List<Map<String, Object>> items = new ArrayList<>();
        for (int cardId : ids) for (int rarity = 0; rarity <= 3; rarity++) items.add(enqueueCard(key, cardId, rarity, count));
        return Map.of("ok", true, "granted", Map.of("mode", "all", "cardIds", ids.size(), "rarities", 4,
                "count", count, "rows", items.size()), "enqueuedCount", items.size(), "snapshot", meta.find(key));
    }

    private Map<String, Object> enqueueCard(String key, int cardId, int rarity, int count) {
        Map<String, Object> card = Map.of("id", cardId, "rarity", rarity, "count", count, "name", cards.name(cardId));
        return mailbox.enqueue(key, "ops", cards.name(cardId), Map.of("cards", List.of(card)));
    }

    List<Map<String, Object>> patchNotes(int limit) { return patchNotes.all(limit); }
    Map<String, Object> createPatchNote(Map<String, Object> body) { return patchNotes.create(body); }
    Map<String, Object> deletePatchNote(Map<String, Object> body) { return patchNotes.delete(body.get("id")); }
    Map<String, Object> maintenance(Map<String, Object> body) {
        Map<String, Object> state = maintenance.write(Boolean.TRUE.equals(body.get("enabled")), String.valueOf(body.getOrDefault("message", "")));
        Map<String, Object> result = new LinkedHashMap<>(); result.put("ok", true); result.putAll(state); return result;
    }
    Map<String, Object> precheck() { return monitor.writePrecheck(); }
    Map<String, Object> backup(Map<String, Object> body) {
        Object value = body.containsKey("label") ? body.get("label") : body.get("name");
        return processes.backup(value == null ? "" : String.valueOf(value));
    }
    Map<String, Object> restore(Map<String, Object> body) { return processes.restore(String.valueOf(body.getOrDefault("name", ""))); }
    Map<String, Object> backups() { return Map.of("ok", true, "backups", processes.list()); }

    private String accountKey(Map<String, Object> body) {
        Object raw = body.containsKey("accountKey") ? body.get("accountKey") : body.get("account_key");
        String key = raw == null ? "" : String.valueOf(raw).trim();
        if (key.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "account_key_required");
        return key;
    }
    private int boundedInteger(Object raw, int min, int max, String error) {
        if (!(raw instanceof Number number) || !Double.isFinite(number.doubleValue()) || number.doubleValue() != Math.floor(number.doubleValue()))
            throw new ApiException(HttpStatus.BAD_REQUEST, error);
        long value = number.longValue();
        if (value < min) throw new ApiException(HttpStatus.BAD_REQUEST, error);
        return (int) Math.min(value, max);
    }
    private String limited(String value, int max) { return value.length() <= max ? value : value.substring(0, max); }
}
