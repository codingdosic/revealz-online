package com.revealz.backend.ops;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
class OpsAuthFilter extends OncePerRequestFilter {
    private final String expected;
    OpsAuthFilter(@Value("${ops.token:}") String expected) { this.expected = expected == null ? "" : expected; }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !(path.equals("/ops") || path.startsWith("/ops/") || path.startsWith("/v1/ops/"));
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = first(request.getParameter("token"), request.getParameter("ops_token"),
                request.getHeader("X-Ops-Token"), bearer(request.getHeader("Authorization")));
        if (expected.isBlank() || !MessageDigest.isEqual(hash(expected), hash(token))) {
            response.setStatus(404); response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"not_found\"}"); return;
        }
        chain.doFilter(request, response);
    }
    private String first(String... values) { for (String value : values) if (value != null && !value.isBlank()) return value.trim(); return ""; }
    private String bearer(String value) {
        return value != null && value.regionMatches(true, 0, "Bearer ", 0, 7) ? value.substring(7).trim() : "";
    }
    private byte[] hash(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
