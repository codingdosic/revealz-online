package com.revealz.backend.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

class OpsMonitorServiceTest {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void collectedLabelUsesTheActualLatestTimestamp() {
        String timestamp = Instant.now().minusSeconds(60).toString();

        String label = OpsMonitorService.collectedLabel(List.of(Map.of("ts", timestamp)), json);

        assertThat(label).contains(timestamp).contains("마지막 수집:");
        assertThat(OpsMonitorService.collectedLabel(List.of(), json)).contains("폴러 기록 없음");
        assertThat(OpsMonitorService.collectedLabel(List.of(Map.of("ts", "broken")), json))
                .isEqualTo("마지막 수집 시각을 읽지 못함");
    }

    @Test
    void monitorPageContainsSummaryChartsTableAndEmptyStates() throws Exception {
        String page = new ClassPathResource("ops_monitor.html").getContentAsString(StandardCharsets.UTF_8);

        assertThat(page).contains("data-field=\"rooms\"", "id=\"charts\"", "id=\"recent-rows\"",
                "데이터 없음", "기록 없음", "요청 실패:", "setInterval(refresh, 30000)");
    }
}
