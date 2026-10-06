package com.revealz.backend.matchlog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import java.util.HexFormat;
import java.time.Instant;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import com.revealz.backend.shop.CardCatalog;
import com.revealz.backend.meta.MetaService;
import com.revealz.backend.web.ApiException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MigrationSliceIntegrationTest {
    private static final String ACCOUNT_KEY = "be11-google-account";
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("revealz_meta").withUsername("postgres").withPassword("postgres");
    static {
        POSTGRES.start();
        try (Connection connection = admin(); Statement statement = connection.createStatement()) {
            statement.execute(Files.readString(Path.of("db/schema.sql")));
        } catch (Exception exception) { throw new ExceptionInInitializerError(exception); }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("catalog.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("catalog.datasource.username", POSTGRES::getUsername);
        registry.add("catalog.datasource.password", POSTGRES::getPassword);
        registry.add("catalog.cache.enabled", () -> false);
        registry.add("ops.token", () -> "test-ops-token");
        registry.add("lobby.worker-bin", () -> "");
        registry.add("auth.jwt-secret", () -> "migration-test-secret-32-bytes-minimum");
    }

    @LocalServerPort int port;
    @Autowired JsonMapper json;
    @Autowired CardCatalog cards;
    @Autowired MatchLogService matchLogs;
    @Autowired MetaService meta;
    @Autowired JwtEncoder jwtEncoder;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void clean() throws Exception {
        try (Connection connection = admin(); Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE match_snapshots, match_events, matches, mailbox_items, decks, owned_cards, "
                    + "owned_accessories, wallets, external_identities, refresh_tokens, accounts, deleted_accounts, "
                    + "shop_products, patch_notes CASCADE");
        }
    }

    @Test
    void preservesAuthenticatedJpaJdbcAndOpsContracts() throws Exception {
        meta.createGoogleAccount(ACCOUNT_KEY, "BE11Test");
        String accessToken = accessToken(ACCOUNT_KEY);
        JsonNode created = body(sendUser("GET", accountPath(), null, accessToken));
        long revision = created.get("metaRevision").longValue();
        assertThat(created.at("/account/authKind").stringValue()).isEqualTo("google");
        assertThat(created.at("/account/displayName").stringValue()).isEqualTo("BE11Test");
        assertThat(send("GET", accountPath(), null, null, null).statusCode()).isEqualTo(401);
        assertThat(sendUser("GET", accountPath(), null, accessToken("another-account")).statusCode()).isEqualTo(403);

        assertThat(sendUser("POST", accountPath() + "/profile",
                "{\"baseRevision\":" + revision + ",\"displayName\":\"BE11Next\"}", accessToken).statusCode())
                .isEqualTo(200);
        assertThat(sendUser("POST", accountPath() + "/profile",
                "{\"baseRevision\":" + revision + ",\"displayName\":\"Stale\"}", accessToken).statusCode())
                .isEqualTo(409);
        assertThat(text("SELECT display_name FROM accounts WHERE account_key='" + ACCOUNT_KEY + "'")).isEqualTo("BE11Next");
        assertThat(scalar("SELECT meta_revision FROM accounts WHERE account_key='" + ACCOUNT_KEY + "'")).isEqualTo(revision + 1);
        assertThat(sendUser("POST", accountPath() + "/validate-deck",
                "{\"card_ids\":[999999],\"card_rarities\":[0]}", accessToken).statusCode()).isEqualTo(409);

        int cardId = cards.nonTokenIds().getFirst();
        sql("UPDATE wallets SET gold=100 WHERE account_key='" + ACCOUNT_KEY + "'");
        sql("INSERT INTO shop_products(product_id,product_type,display_name,price_gold,pack_size,weight_n,pool_mode,pool_json) "
                + "VALUES('once','pack','Once',100,1,1,'explicit','[" + cardId + "]')");
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var one = executor.submit(() -> sendUser("POST", accountPath() + "/purchase", "{\"product_id\":\"once\"}", accessToken).statusCode());
            var two = executor.submit(() -> sendUser("POST", accountPath() + "/purchase", "{\"product_id\":\"once\"}", accessToken).statusCode());
            assertThat(java.util.List.of(one.get(), two.get())).containsExactlyInAnyOrder(200, 409);
        }
        assertThat(scalar("SELECT gold FROM wallets WHERE account_key='" + ACCOUNT_KEY + "'")).isZero();
        assertThat(scalar("SELECT SUM(count) FROM owned_cards WHERE account_key='" + ACCOUNT_KEY + "'")).isEqualTo(1);
        assertThat(sendUser("POST", accountPath() + "/purchase", "{\"product_id\":\"once\"}", accessToken).statusCode())
                .isEqualTo(409);
        assertThat(scalar("SELECT SUM(count) FROM owned_cards WHERE account_key='" + ACCOUNT_KEY + "'")).isEqualTo(1);

        long mailboxId = scalar("INSERT INTO mailbox_items(account_key,source,title,payload,status) "
                + "VALUES('" + ACCOUNT_KEY + "','test','one','{\"gold\":7}','pending') RETURNING id");
        String claim = "{\"id\":\"" + mailboxId + "\"}";
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var one = executor.submit(() -> sendUser("POST", accountPath() + "/mailbox/claim", claim, accessToken).statusCode());
            var two = executor.submit(() -> sendUser("POST", accountPath() + "/mailbox/claim", claim, accessToken).statusCode());
            assertThat(java.util.List.of(one.get(), two.get())).containsExactlyInAnyOrder(200, 409);
        }
        assertThat(scalar("SELECT gold FROM wallets WHERE account_key='" + ACCOUNT_KEY + "'")).isEqualTo(7);

        String oldRefresh = "be11-refresh-token";
        sql("INSERT INTO refresh_tokens(token_hash,account_key,expires_at) VALUES('" + hash(oldRefresh) + "','"
                + ACCOUNT_KEY + "',NOW()+INTERVAL '10 minutes')");
        assertThat(send("POST", "/v1/auth/refresh", "{\"refreshToken\":\"" + oldRefresh + "\"}", null, null)
                .statusCode()).isEqualTo(200);
        assertThat(send("POST", "/v1/auth/refresh", "{\"refreshToken\":\"" + oldRefresh + "\"}", null, null)
                .statusCode()).isEqualTo(401);
        assertThat(scalar("SELECT COUNT(*) FROM refresh_tokens WHERE account_key='" + ACCOUNT_KEY
                + "' AND revoked_at IS NULL")).isEqualTo(1);

        UUID matchId = UUID.fromString("123e4567-e89b-42d3-a456-426614174000");
        JsonNode finalPayload = finalPayload(matchId, "finished");
        assertThat(matchLogs.store(finalPayload, matchId).duplicate()).isFalse();
        assertThat(text("SELECT payload_sha256 FROM matches WHERE match_id='" + matchId + "'"))
                .isEqualTo("ab2490c9bd2a5d58981daaa1dcea384fd354beb8452c427a9dff9ef0828e3548");
        assertThat(matchLogs.store(finalPayload, matchId).duplicate()).isTrue();
        assertThatThrownBy(() -> matchLogs.store(finalPayload(matchId, "changed"), matchId))
                .isInstanceOf(ApiException.class);
        assertThat(send("POST", "/v1/internal/matches/" + matchId + "/logs/final",
                finalPayload.toString(), null, null).statusCode()).isEqualTo(401);

        assertThat(send("GET", "/v1/ops/accounts", null, null, null).statusCode()).isEqualTo(404);
        assertThat(sendOps("GET", "/v1/ops/accounts", null).statusCode()).isEqualTo(200);
        long beforeOpsRevision = scalar("SELECT meta_revision FROM accounts WHERE account_key='" + ACCOUNT_KEY + "'");
        HttpResponse<String> patched = sendOps("POST", "/v1/ops/account",
                "{\"accountKey\":\"" + ACCOUNT_KEY + "\",\"displayName\":\"OpsName\",\"gold\":9}");
        assertThat(patched.statusCode()).isEqualTo(200);
        assertThat(body(patched).at("/snapshot/gold").intValue()).isEqualTo(9);
        assertThat(body(patched).at("/snapshot/account/displayName").stringValue()).isEqualTo("OpsName");
        assertThat(body(patched).at("/snapshot/metaRevision").longValue()).isEqualTo(beforeOpsRevision + 1);

        HttpResponse<String> noteCreated = sendOps("POST", "/v1/ops/patch-notes",
                "{\"title\":\"BE11\",\"body\":\"JPA note\"}");
        assertThat(noteCreated.statusCode()).isEqualTo(200);
        long noteId = body(noteCreated).at("/note/id").longValue();
        assertThat(body(send("GET", "/v1/patch-notes", null, null, null)).at("/notes/0/id").longValue())
                .isEqualTo(noteId);
        assertThat(sendOps("POST", "/v1/ops/patch-notes/delete", "{\"id\":" + noteId + "}").statusCode())
                .isEqualTo(200);
        assertThat(send("GET", "/v1/patch-notes/" + noteId, null, null, null).statusCode()).isEqualTo(404);
    }

    private JsonNode finalPayload(UUID id, String reason) {
        return json.valueToTree(Map.of(
                "schemaVersion", 1, "matchId", id.toString(),
                "summary", Map.of("status", "completed", "startedAtUnixMs", 1000, "endedAtUnixMs", 2000,
                        "winnerSide", 0, "reason", reason, "players", java.util.List.of()),
                "events", java.util.List.of(Map.of("schemaVersion", 1, "matchId", id.toString(), "seq", 1,
                        "occurredAtUnixMs", 2000, "round", 1, "phase", "END", "type", "MATCH_FINISHED",
                        "actorSide", 0, "payload", Map.of())),
                "snapshots", java.util.List.of()));
    }

    private HttpResponse<String> sendUser(String method, String path, String payload, String accessToken) throws Exception {
        return send(method, path, payload, accessToken, null);
    }

    private HttpResponse<String> sendOps(String method, String path, String payload) throws Exception {
        return send(method, path, payload, null, "test-ops-token");
    }

    private HttpResponse<String> send(String method, String path, String payload,
            String accessToken, String opsToken) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (accessToken != null) builder.header("Authorization", "Bearer " + accessToken);
        if (opsToken != null) builder.header("X-Ops-Token", opsToken);
        if (payload != null) builder.header("Content-Type", "application/json");
        return http.send(builder.method(method, payload == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(payload)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private String accountPath() { return "/v1/meta/accounts/" + ACCOUNT_KEY; }
    private String accessToken(String subject) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer("revealz-backend").subject(subject)
                .audience(java.util.List.of("revealz-windows")).issuedAt(now).expiresAt(now.plusSeconds(300)).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
    private String hash(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
    private JsonNode body(HttpResponse<String> response) { return json.readTree(response.body()); }
    private void sql(String value) throws Exception { try (Connection c = admin(); Statement s = c.createStatement()) { s.execute(value); } }
    private long scalar(String value) throws Exception {
        try (Connection c = admin(); Statement s = c.createStatement(); var rs = s.executeQuery(value)) { rs.next(); return rs.getLong(1); }
    }
    private String text(String value) throws Exception {
        try (Connection c = admin(); Statement s = c.createStatement(); var rs = s.executeQuery(value)) { rs.next(); return rs.getString(1); }
    }
    private static Connection admin() throws Exception { return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()); }
    @AfterAll static void stop() { POSTGRES.stop(); }
}
