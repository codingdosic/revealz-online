package com.revealz.backend.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.StreamUtils;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@ActiveProfiles("local-account")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccountIntegrationTest {

    private static final String READER = "account_reader";
    private static final String READER_PASSWORD = "local-test-only";
    private static final String ACCOUNT_KEY = "be05-account";
    private static final String DELETED_KEY = "be05-deleted";
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
        registry.add("auth.jwt-secret", () -> "local-account-test-secret-32-bytes-minimum");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private JwtEncoder jwtEncoder;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void seedFixture() throws SQLException {
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM deleted_accounts WHERE account_key = '" + DELETED_KEY + "'");
            statement.executeUpdate("DELETE FROM accounts WHERE account_key = '" + ACCOUNT_KEY + "'");
            statement.executeUpdate("""
                    INSERT INTO accounts (account_key, display_name, profile_icon_id, meta_revision)
                    VALUES ('be05-account', 'Before', 'icon-preserved', 7)
                    """);
            statement.executeUpdate("""
                    INSERT INTO deleted_accounts (account_key)
                    VALUES ('be05-deleted')
                    """);
        }
    }

    @Test
    void getsExistingAccountAndDistinguishesMissingFromDeleted() throws Exception {
        HttpResponse<String> existing = request("GET", "/v1/local/accounts/" + ACCOUNT_KEY, null, ACCOUNT_KEY);
        JsonNode body = jsonMapper.readTree(existing.body());

        assertThat(existing.statusCode()).isEqualTo(200);
        assertThat(body.get("accountKey").stringValue()).isEqualTo(ACCOUNT_KEY);
        assertThat(body.get("displayName").stringValue()).isEqualTo("Before");
        assertThat(body.get("metaRevision").longValue()).isEqualTo(7);
        assertThat(request("GET", "/v1/local/accounts/missing", null, "missing").statusCode()).isEqualTo(404);
        assertThat(request("GET", "/v1/local/accounts/" + DELETED_KEY, null, DELETED_KEY).statusCode()).isEqualTo(410);
        assertThat(request("GET", "/v1/local/accounts/" + ACCOUNT_KEY, null, "another-account").statusCode())
                .isEqualTo(403);
    }

    @Test
    void changesNameOnceAndRejectsTheSameStaleRevision() throws Exception {
        HttpResponse<String> changed = request(
                "PATCH",
                "/v1/local/accounts/" + ACCOUNT_KEY,
                "{\"displayName\":\"  새_Name-1  \",\"baseRevision\":7}", ACCOUNT_KEY);
        JsonNode changedBody = jsonMapper.readTree(changed.body());

        assertThat(changed.statusCode()).isEqualTo(200);
        assertThat(changedBody.get("displayName").stringValue()).isEqualTo("새_Name-1");
        assertThat(changedBody.get("metaRevision").longValue()).isEqualTo(8);

        HttpResponse<String> stale = request(
                "PATCH",
                "/v1/local/accounts/" + ACCOUNT_KEY,
                "{\"displayName\":\"Stale\",\"baseRevision\":7}", ACCOUNT_KEY);

        assertThat(stale.statusCode()).isEqualTo(409);
        assertThat(accountValue("display_name")).isEqualTo("새_Name-1");
        assertThat(accountValue("meta_revision")).isEqualTo("8");
        assertThat(accountValue("profile_icon_id")).isEqualTo("icon-preserved");
    }

    @Test
    void rejectsInvalidDisplayName() throws Exception {
        HttpResponse<String> invalid = request(
                "PATCH",
                "/v1/local/accounts/" + ACCOUNT_KEY,
                "{\"displayName\":\"bad name!\",\"baseRevision\":7}", ACCOUNT_KEY);
        HttpResponse<String> fractionalRevision = request(
                "PATCH",
                "/v1/local/accounts/" + ACCOUNT_KEY,
                "{\"displayName\":\"ValidName\",\"baseRevision\":7.5}", ACCOUNT_KEY);

        assertThat(invalid.statusCode()).isEqualTo(400);
        assertThat(fractionalRevision.statusCode()).isEqualTo(400);
        assertThat(accountValue("display_name")).isEqualTo("Before");
        assertThat(accountValue("meta_revision")).isEqualTo("7");
    }

    @Test
    void databaseRoleCannotModifyUnrelatedAccountColumns() {
        assertThatThrownBy(() -> {
            try (Connection connection = DriverManager.getConnection(
                    POSTGRES.getJdbcUrl(), READER, READER_PASSWORD);
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE accounts SET auth_kind = 'other'");
            }
        }).isInstanceOf(SQLException.class)
                .extracting(exception -> ((SQLException) exception).getSQLState())
                .isEqualTo("42501");
    }

    @AfterAll
    static void stopContainer() {
        if (POSTGRES.isRunning()) {
            POSTGRES.stop();
        }
    }

    private HttpResponse<String> request(String method, String path, String body, String subject)
            throws IOException, InterruptedException {
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body);
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).method(method, publisher);
        request.header("Authorization", "Bearer " + accessToken(subject));
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String accessToken(String subject) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("revealz-backend")
                .subject(subject)
                .audience(java.util.List.of("revealz-windows"))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .build();
        return jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private String accountValue(String column) throws SQLException {
        try (Connection connection = adminConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT " + column + "::text FROM accounts WHERE account_key = '" + ACCOUNT_KEY + "'")) {
            result.next();
            return result.getString(1);
        }
    }

    private static Connection adminConnection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static void initializeDatabase() {
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            String schema = StreamUtils.copyToString(
                    new ClassPathResource("account-test-schema.sql").getInputStream(),
                    StandardCharsets.UTF_8);
            statement.execute(schema);
            statement.execute("CREATE ROLE " + READER + " LOGIN PASSWORD '" + READER_PASSWORD + "'");
            statement.execute("GRANT CONNECT ON DATABASE revealz TO " + READER);
            statement.execute("GRANT USAGE ON SCHEMA public TO " + READER);
            statement.execute("GRANT SELECT ON accounts, deleted_accounts TO " + READER);
            statement.execute("GRANT UPDATE (display_name, meta_revision) ON accounts TO " + READER);
        } catch (IOException | SQLException exception) {
            throw new IllegalStateException("Failed to initialize account test database", exception);
        }
    }
}
