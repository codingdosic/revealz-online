package com.revealz.backend.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.StreamUtils;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(OrderAnnotation.class)
class CatalogIntegrationTest {

    private static final String READER = "catalog_reader";
    private static final String READER_PASSWORD = "local-test-only";
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("revealz")
            .withUsername("postgres")
            .withPassword("postgres");

    static {
        POSTGRES.start();
        initializeDatabase();
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("catalog.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("catalog.datasource.username", () -> READER);
        registry.add("catalog.datasource.password", () -> READER_PASSWORD);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JsonMapper jsonMapper;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void seedFixture() throws SQLException {
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE shop_products, app_config");
            statement.executeUpdate("""
                    INSERT INTO shop_products (
                      product_id, product_type, display_name, description, price_gold, enabled,
                      pack_size, weight_n, weight_r, weight_sr, weight_ur, pool_mode, pool_json,
                      accessory_type, accessory_id, sort_order
                    ) VALUES
                      ('late', 'pack', 'Late', '', 10, TRUE, 2, 1, 2, 3, 4,
                       'explicit', '[3.9, "2", null, "bad", -1]'::jsonb, '', '', 20),
                      ('early-b', 'pack', 'Early B', '', 20, TRUE, 5, 70, 20, 8, 2,
                       'all_non_token', '[99]'::jsonb, '', '', 10),
                      ('early-a', 'accessory', 'Early A', 'field', 30, TRUE, 1, 0, 0, 0, 0,
                       'explicit', '[7]'::jsonb, 'field', 'field-blue', 10),
                      ('disabled', 'pack', 'Hidden', '', 40, FALSE, 5, 1, 1, 1, 1,
                       'explicit', '[8]'::jsonb, '', '', 0)
                    """);
            statement.executeUpdate("""
                    INSERT INTO app_config (config_key, config_value)
                    VALUES ('shop_catalog_revision', '"12"'::jsonb)
                    """);
        }
    }

    @Test
    @Order(1)
    void returnsEnabledSortedCatalogWithNodeNormalization() throws Exception {
        HttpResponse<String> response = request("GET", "/v1/shop/catalog?ignored=yes");
        JsonNode body = jsonMapper.readTree(response.body());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(body.get("revision").decimalValue()).isEqualByComparingTo("12");
        assertThat(body.get("products").size()).isEqualTo(3);
        assertThat(body.get("products").get(0).get("productId").stringValue()).isEqualTo("early-a");
        assertThat(body.get("products").get(1).get("productId").stringValue()).isEqualTo("early-b");
        assertThat(body.get("products").get(1).get("pool").isEmpty()).isTrue();
        assertThat(body.get("products").get(2).get("pool").toString()).isEqualTo("[3,2]");
        assertThat(body.get("products").get(0).has("enabled")).isFalse();
    }

    @Test
    @Order(2)
    void returnsEmptyProductsAndRevisionFallbacks() throws Exception {
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE shop_products");
            statement.executeUpdate("UPDATE app_config SET config_value = '\"not-a-number\"'::jsonb");
        }
        JsonNode invalid = jsonMapper.readTree(request("GET", "/v1/shop/catalog").body());
        assertThat(invalid.get("revision").decimalValue()).isEqualByComparingTo("0");
        assertThat(invalid.get("products").isEmpty()).isTrue();

        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM app_config");
        }
        JsonNode missing = jsonMapper.readTree(request("GET", "/v1/shop/catalog").body());
        assertThat(missing.get("revision").decimalValue()).isEqualByComparingTo("0");
    }

    @Test
    @Order(3)
    void appliesNullAndLowerBoundDefaults() throws Exception {
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE shop_products");
            statement.executeUpdate("""
                    INSERT INTO shop_products (
                      product_id, product_type, display_name, description, price_gold, enabled,
                      pack_size, weight_n, weight_r, weight_sr, weight_ur, pool_mode, pool_json,
                      accessory_type, accessory_id, sort_order
                    ) VALUES ('defaults', 'pack', NULL, NULL, NULL, TRUE,
                              NULL, -2, NULL, 0, NULL, NULL, NULL, NULL, NULL, NULL)
                    """);
            statement.executeUpdate("UPDATE app_config SET config_value = 'null'::jsonb");
        }
        JsonNode body = jsonMapper.readTree(request("GET", "/v1/shop/catalog").body());
        JsonNode product = body.get("products").get(0);

        assertThat(body.get("revision").decimalValue()).isEqualByComparingTo("0");
        assertThat(product.get("displayName").stringValue()).isEmpty();
        assertThat(product.get("packSize").intValue()).isEqualTo(1);
        assertThat(product.get("weightN").intValue()).isZero();
        assertThat(product.get("weightR").intValue()).isZero();
        assertThat(product.get("poolMode").stringValue()).isEqualTo("explicit");
        assertThat(product.get("pool").isEmpty()).isTrue();
    }

    @Test
    @Order(4)
    void preservesMethodContractAndRequestIdRules() throws Exception {
        HttpResponse<String> post = request("POST", "/v1/shop/catalog");
        assertThat(post.statusCode()).isEqualTo(405);
        assertThat(jsonMapper.readTree(post.body()).get("error").stringValue())
                .isEqualTo("method_not_allowed");

        HttpResponse<String> head = request("HEAD", "/v1/shop/catalog");
        assertThat(head.statusCode()).isEqualTo(405);
        assertThat(head.body()).isEmpty();

        HttpResponse<String> options = request("OPTIONS", "/v1/shop/catalog");
        assertThat(options.statusCode()).isEqualTo(204);

        HttpRequest safeIdRequest = HttpRequest.newBuilder(uri("/v1/shop/catalog"))
                .header("X-Request-Id", "client_123")
                .GET()
                .build();
        assertThat(http.send(safeIdRequest, HttpResponse.BodyHandlers.ofString())
                .headers().firstValue("X-Request-Id")).contains("client_123");
    }

    @Test
    @Order(5)
    void getDoesNotMutateDataAndReaderCannotWrite() throws Exception {
        long before = scalar("SELECT COUNT(*) FROM shop_products");
        request("GET", "/v1/shop/catalog");
        assertThat(scalar("SELECT COUNT(*) FROM shop_products")).isEqualTo(before);

        assertThatThrownBy(() -> {
            try (Connection connection = DriverManager.getConnection(
                    POSTGRES.getJdbcUrl(), READER, READER_PASSWORD);
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate("DELETE FROM shop_products");
            }
        }).isInstanceOf(SQLException.class)
                .extracting(exception -> ((SQLException) exception).getSQLState())
                .isEqualTo("42501");
    }

    @Test
    @Order(99)
    void masksDatabaseFailureAndKeepsLivenessSeparate() throws Exception {
        POSTGRES.stop();

        HttpResponse<String> catalog = request("GET", "/v1/shop/catalog");
        JsonNode error = jsonMapper.readTree(catalog.body());
        assertThat(catalog.statusCode()).isEqualTo(500);
        assertThat(error.get("error").stringValue()).isEqualTo("internal");
        assertThat(error.get("message").stringValue()).isEqualTo("catalog_database_unavailable");
        assertThat(catalog.body()).doesNotContain("jdbc", "postgres", "password", "Connection");
        assertThat(request("GET", "/actuator/health/liveness").statusCode()).isEqualTo(200);
        assertThat(request("GET", "/actuator/health/readiness").statusCode()).isEqualTo(503);
    }

    @AfterAll
    static void stopContainer() {
        if (POSTGRES.isRunning()) {
            POSTGRES.stop();
        }
    }

    private HttpResponse<String> request(String method, String path)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private long scalar(String sql) throws SQLException {
        try (Connection connection = adminConnection();
             Statement statement = connection.createStatement();
             var resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private static Connection adminConnection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static void initializeDatabase() {
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            String schema = StreamUtils.copyToString(
                    new ClassPathResource("catalog-test-schema.sql").getInputStream(),
                    StandardCharsets.UTF_8);
            statement.execute(schema);
            statement.execute("CREATE ROLE " + READER + " LOGIN PASSWORD '" + READER_PASSWORD + "'");
            statement.execute("GRANT CONNECT ON DATABASE revealz TO " + READER);
            statement.execute("GRANT USAGE ON SCHEMA public TO " + READER);
            statement.execute("GRANT SELECT ON shop_products, app_config TO " + READER);
        } catch (IOException | SQLException exception) {
            throw new IllegalStateException("Failed to initialize catalog test database", exception);
        }
    }
}
