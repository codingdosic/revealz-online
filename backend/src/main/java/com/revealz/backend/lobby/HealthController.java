package com.revealz.backend.lobby;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class HealthController {
    private final HealthService healthService;

    HealthController(HealthService healthService) { this.healthService = healthService; }

    @GetMapping("/v1/health")
    Map<String, Object> health() { return healthService.snapshot(); }
}
