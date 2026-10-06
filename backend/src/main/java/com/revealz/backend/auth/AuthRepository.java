package com.revealz.backend.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class AuthRepository {
    private final JdbcTemplate jdbc;
    AuthRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    void lockIdentity(String provider, String subject) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtext(?))", rs -> null, provider + ":" + subject);
    }
}
