package com.revealz.backend.ops;

import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class OpsController {
    private final OpsService service;
    private final OpsMonitorService monitor;

    OpsController(OpsService service, OpsMonitorService monitor) { this.service = service; this.monitor = monitor; }

    @GetMapping(value = {"/ops", "/ops/monitor"}, produces = MediaType.TEXT_HTML_VALUE)
    String monitor() { return monitor.monitorPage(); }

    @GetMapping(value = "/ops/db", produces = MediaType.TEXT_HTML_VALUE)
    String databasePage() { return monitor.dbPage(); }

    @GetMapping("/v1/ops/monitor") Map<String, Object> monitorPayload() { return monitor.payload(); }
    @GetMapping("/v1/ops/accounts") Map<String, Object> accounts(
            @RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "created") String sort,
            @RequestParam(defaultValue = "desc") String order, @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int limit) { return service.accounts(q, sort, order, page, limit); }
    @GetMapping("/v1/ops/account") Map<String, Object> account(
            @RequestParam(required = false) String key, @RequestParam(required = false) String displayName) {
        return service.account(key, displayName);
    }
    @GetMapping("/v1/ops/backups") Map<String, Object> backups() { return service.backups(); }
    @GetMapping("/v1/ops/patch-notes") Map<String, Object> patchNotes(@RequestParam(defaultValue = "100") int limit) {
        return Map.of("ok", true, "notes", service.patchNotes(limit));
    }

    @PostMapping("/v1/ops/account") Map<String, Object> patchAccount(@RequestBody Map<String, Object> body) { return service.patchAccount(body); }
    @PostMapping("/v1/ops/account/delete") Map<String, Object> deleteAccount(@RequestBody Map<String, Object> body) { return service.deleteAccount(body); }
    @PostMapping("/v1/ops/grant") Map<String, Object> grant(@RequestBody Map<String, Object> body) { return service.grant(body); }
    @PostMapping("/v1/ops/maintenance") Map<String, Object> maintenance(@RequestBody Map<String, Object> body) { return service.maintenance(body); }
    @PostMapping("/v1/ops/precheck") Map<String, Object> precheck() { return service.precheck(); }
    @PostMapping("/v1/ops/backup") Map<String, Object> backup(@RequestBody Map<String, Object> body) { return service.backup(body); }
    @PostMapping("/v1/ops/restore") Map<String, Object> restore(@RequestBody Map<String, Object> body) { return service.restore(body); }
    @PostMapping("/v1/ops/patch-notes") Map<String, Object> createPatchNote(@RequestBody Map<String, Object> body) { return service.createPatchNote(body); }
    @PostMapping("/v1/ops/patch-notes/delete") Map<String, Object> deletePatchNote(@RequestBody Map<String, Object> body) { return service.deletePatchNote(body); }
}
