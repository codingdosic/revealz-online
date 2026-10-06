package com.revealz.backend.observability;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(RequestLoggingFilter.class);
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private final String instanceId;

    RequestLoggingFilter(@Value("${app.instance-id:}") String instanceId) {
        this.instanceId = instanceId.strip();
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String requestId = requestId(request.getHeader("X-Request-Id"));
        long started = System.nanoTime();
        response.setHeader("X-Request-Id", requestId);
        if (!instanceId.isEmpty()) {
            response.setHeader("X-Instance-Id", instanceId);
        }
        MDC.put("requestId", requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            long elapsedMs = (System.nanoTime() - started) / 1_000_000;
            LOG.info("request method={} path={} status={} elapsedMs={}",
                    request.getMethod(), request.getRequestURI(), response.getStatus(), elapsedMs);
            MDC.remove("requestId");
        }
    }

    private static String requestId(String candidate) {
        return candidate != null && SAFE_REQUEST_ID.matcher(candidate).matches()
                ? candidate
                : UUID.randomUUID().toString();
    }
}
