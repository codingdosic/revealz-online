package com.revealz.backend.patchnote;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/patch-notes")
class PatchNoteController {
    private final PatchNoteService service;
    PatchNoteController(PatchNoteService service) { this.service = service; }

    @GetMapping Map<String, Object> list(@RequestParam(defaultValue = "50") int limit) {
        List<Map<String, Object>> notes = service.published(limit);
        return Map.of("ok", true, "notes", notes);
    }
    @GetMapping("/{id}") Map<String, Object> one(@PathVariable long id) {
        return Map.of("ok", true, "note", service.published(id));
    }
}
