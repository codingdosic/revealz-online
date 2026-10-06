package com.revealz.backend.patchnote;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.revealz.backend.web.ApiException;

@Service
@Transactional(readOnly = true)
public class PatchNoteService {
    private final PatchNoteRepository repository;
    PatchNoteService(PatchNoteRepository repository) { this.repository = repository; }

    List<Map<String, Object>> published(int limit) {
        return repository.findByPublishedAtLessThanEqualOrderByPublishedAtDescIdDesc(
                Instant.now(), PageRequest.of(0, Math.max(1, Math.min(100, limit))))
                .stream().map(note -> response(note, false)).toList();
    }
    Map<String, Object> published(long id) {
        PatchNote note = repository.findByIdAndPublishedAtLessThanEqual(id, Instant.now())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "not_found"));
        return response(note, true);
    }
    public List<Map<String, Object>> all(int limit) {
        return repository.findAllByOrderByPublishedAtDescIdDesc(
                PageRequest.of(0, Math.max(1, Math.min(200, limit))))
                .stream().map(note -> response(note, true)).toList();
    }
    @Transactional
    public Map<String, Object> create(Map<String, Object> body) {
        String title = limited(text(body.get("title")).trim(), 200);
        if (title.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "title_required");
        String content = limited(text(body.get("body")), 50_000);
        Instant publishAt = Instant.now();
        if (body.get("publishAt") != null && !text(body.get("publishAt")).isBlank()) {
            try { publishAt = Instant.parse(text(body.get("publishAt"))); }
            catch (DateTimeParseException exception) { throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_publish_at"); }
        }
        PatchNote note = repository.save(new PatchNote(title, content, publishAt));
        return Map.of("ok", true, "note", response(note, true));
    }
    @Transactional
    public Map<String, Object> delete(Object raw) {
        long id = positiveId(raw);
        PatchNote note = repository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "not_found"));
        repository.delete(note);
        return Map.of("ok", true, "deletedId", id);
    }
    private Map<String, Object> response(PatchNote note, boolean includeBody) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", note.id());
        result.put("title", note.title());
        result.put("publishedAt", note.publishedAt().toString());
        result.put("createdAt", note.createdAt().toString());
        result.put("updatedAt", note.updatedAt().toString());
        if (includeBody) result.put("body", note.body());
        return result;
    }
    private long positiveId(Object raw) {
        if (!(raw instanceof Number number) || number.doubleValue() != Math.rint(number.doubleValue()) || number.longValue() <= 0)
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_id");
        return number.longValue();
    }
    private String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private String limited(String value, int max) { return value.length() <= max ? value : value.substring(0, max); }
}
