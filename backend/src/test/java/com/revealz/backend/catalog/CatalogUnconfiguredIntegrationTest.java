package com.revealz.backend.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "catalog.datasource.url=",
                "app.instance-id=test-instance"
        })
class CatalogUnconfiguredIntegrationTest {

    @LocalServerPort
    private int port;

    @Test
    void startsWithoutDatabaseAndReportsAvailabilityHonestly() throws Exception {
        HttpResponse<String> catalog = get("/v1/shop/catalog");
        assertThat(catalog.statusCode()).isEqualTo(503);
        assertThat(catalog.body()).contains("meta_db_not_configured");
        assertThat(catalog.headers().firstValue("X-Instance-Id")).contains("test-instance");
        assertThat(get("/actuator/health/liveness").statusCode()).isEqualTo(200);
        assertThat(get("/actuator/health/readiness").statusCode()).isEqualTo(503);
        assertThat(get("/v1/local/accounts/not-enabled").statusCode()).isEqualTo(401);
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + path)).GET().build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }
}
