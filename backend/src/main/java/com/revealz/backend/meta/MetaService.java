package com.revealz.backend.meta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.revealz.backend.web.ApiException;
import com.revealz.backend.account.Account;
import com.revealz.backend.account.AccountRepository;
import com.revealz.backend.account.DeletedAccountRepository;

@Service
@Transactional(readOnly = true)
public class MetaService {
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9가-힣_-]+$");
    private static final List<String> ACCESSORY_TYPES = List.of("icon", "card_back", "field");
    private final MetaRepository repository;
    private final AccountRepository accounts;
    private final DeletedAccountRepository deletedAccounts;

    MetaService(MetaRepository repository, AccountRepository accounts, DeletedAccountRepository deletedAccounts) {
        this.repository = repository;
        this.accounts = accounts;
        this.deletedAccounts = deletedAccounts;
    }

    public Map<String, Object> find(String accountKey) {
        String key = requiredKey(accountKey);
        Map<String, Object> snapshot = repository.loadSnapshot(key);
        if (snapshot != null) return snapshot;
        if (deletedAccounts.existsById(key)) throw new ApiException(HttpStatus.GONE, "account_deleted");
        throw new ApiException(HttpStatus.NOT_FOUND, "account_not_found");
    }

    @Transactional
    Map<String, Object> upsert(String accountKey, Map<String, Object> body) {
        String key = requiredKey(accountKey);
        if (deletedAccounts.existsById(key)) throw new ApiException(HttpStatus.GONE, "account_deleted");
        Account accountEntity = accounts.findByAccountKey(key)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "account_not_found"));
        if (integer(body.get("baseRevision"), Long.MIN_VALUE) != accountEntity.metaRevision())
            throw revisionConflict(key);
        Map<String, Object> account = MetaRepository.map(body.get("account"));
        String rawName = MetaRepository.textOr(account.get("displayName"), MetaRepository.textOr(body.get("displayName"), key));
        String name = validNameOrFallback(rawName, key);
        String wantedIcon = MetaRepository.limited(MetaRepository.textOr(account.get("profileIconId"),
                MetaRepository.text(body.get("profileIconId"))).trim(), 128);
        String oldIcon = accountEntity.profileIconId();
        String icon = wantedIcon.isEmpty() || repository.ownsAccessory(key, "icon", wantedIcon) ? wantedIcon : oldIcon;
        accountEntity.updateProfile(name, icon);
        repository.replaceDecks(key, mapList(body.get("decks")));
        accounts.flush();
        return repository.loadSnapshot(key);
    }

    @Transactional
    public void createGoogleAccount(String accountKey, String proposedName) {
        String key = requiredKey(accountKey);
        if (accounts.findByAccountKey(key).isEmpty()) {
            String name = validNameOrFallback(proposedName, "Player_" + key.substring(Math.max(0, key.length() - 8)));
            accounts.saveAndFlush(new Account(key, "google", name));
            repository.createWallet(key);
            for (String type : ACCESSORY_TYPES) repository.addAccessory(key, type, defaultAccessory(type));
            repository.enqueueWelcome(key);
        }
    }

    @Transactional
    Map<String, Object> updateProfile(String accountKey, Map<String, Object> body) {
        String key = requiredKey(accountKey);
        if (deletedAccounts.existsById(key)) throw new ApiException(HttpStatus.GONE, "account_deleted");
        Account account = accounts.findByAccountKey(key)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "account_not_found"));
        if (integer(body.get("baseRevision"), Long.MIN_VALUE) != account.metaRevision()) throw revisionConflict(key);
        String name = account.displayName();
        if (body.containsKey("displayName") || body.containsKey("display_name"))
            name = requireName(body.containsKey("displayName") ? body.get("displayName") : body.get("display_name"));
        String icon = account.profileIconId();
        if (body.containsKey("profileIconId") || body.containsKey("profile_icon_id")) {
            Object raw = body.containsKey("profileIconId") ? body.get("profileIconId") : body.get("profile_icon_id");
            icon = MetaRepository.limited(MetaRepository.text(raw).trim(), 128);
            if (!icon.isEmpty() && !repository.ownsAccessory(key, "icon", icon))
                throw new ApiException(HttpStatus.CONFLICT, "icon_not_owned");
        }
        account.updateProfile(name, icon);
        accounts.flush();
        return repository.loadSnapshot(key);
    }

    public Map<String, Object> validateDeck(String accountKey, Map<String, Object> body) {
        String key = requiredKey(accountKey);
        List<?> ids = MetaRepository.list(body.containsKey("card_ids") ? body.get("card_ids") : body.get("cardIds"));
        List<?> rarities = MetaRepository.list(body.containsKey("card_rarities") ? body.get("card_rarities") : body.get("cardRarities"));
        if (ids.isEmpty()) throw new ApiException(HttpStatus.CONFLICT, "empty_deck");
        if (!accounts.existsById(key)) throw new ApiException(HttpStatus.CONFLICT, "account_not_found");
        Map<String, Object> result = repository.validateOwnedDeck(key, ids, rarities);
        if (!Boolean.TRUE.equals(result.get("ok"))) throw new ApiException(HttpStatus.CONFLICT, result);
        return result;
    }

    private ApiException revisionConflict(String key) {
        return new ApiException(HttpStatus.CONFLICT, Map.of("error", "revision_conflict", "snapshot", repository.loadSnapshot(key)));
    }
    private String requiredKey(String value) {
        String key = value == null ? "" : value.trim();
        if (key.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "account_key_required");
        return key;
    }
    private String requireName(Object raw) {
        String name = MetaRepository.text(raw).trim();
        if (name.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "display_name_required");
        if (name.length() > 50) throw new ApiException(HttpStatus.BAD_REQUEST, "display_name_too_long");
        if (!NAME.matcher(name).matches()) throw new ApiException(HttpStatus.BAD_REQUEST, "display_name_invalid");
        return name;
    }
    private String validNameOrFallback(String raw, String key) {
        try { return requireName(raw); } catch (ApiException ignored) { return MetaRepository.limited(key, 50); }
    }
    private String defaultAccessory(String type) {
        return switch (type) { case "icon" -> "icon_default"; case "card_back" -> "card_back_default"; default -> "default_field"; };
    }
    private long integer(Object value, long fallback) {
        if (!(value instanceof Number number)) return fallback;
        double decimal = number.doubleValue();
        return Double.isFinite(decimal) && decimal == Math.rint(decimal) ? number.longValue() : fallback;
    }
    private List<Map<String, Object>> mapList(Object value) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : MetaRepository.list(value)) if (item instanceof Map<?, ?>) result.add(MetaRepository.map(item));
        return result;
    }
}
