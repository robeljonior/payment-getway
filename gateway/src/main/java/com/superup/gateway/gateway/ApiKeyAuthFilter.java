package com.superup.gateway.gateway;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Layer 1, check "who is calling?" and "is it allowed?" for the Payment API.
 *
 * <ul>
 *   <li>The key comes as {@code Authorization: Bearer sk_test_...} or {@code sk_live_...}.</li>
 *   <li>We keep only a SHA-256 hash of each key, so a stolen database doesn't give away keys.</li>
 *   <li>The key decides the client and the mode. A live key needs a LIVE client.</li>
 *   <li>Then the rate limits are checked, per key and for creating sessions.</li>
 * </ul>
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private final JdbcTemplate db;
    private final RateLimiter rateLimiter;
    private final ErrorWriter errors;

    public ApiKeyAuthFilter(JdbcTemplate db, RateLimiter rateLimiter, ErrorWriter errors) {
        this.db = db;
        this.rateLimiter = rateLimiter;
        this.errors = errors;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String ip = request.getRemoteAddr();
        try {
            Caller caller = authenticate(request, ip);
            checkRateLimits(caller, request, response);
            request.setAttribute(Caller.ATTRIBUTE, caller);
        } catch (ApiException e) {
            if ("authentication_failed".equals(e.type()) && !rateLimiter.perIp(ip).tryTake(System.nanoTime())) {
                e = ApiException.rateLimited();
            }
            if ("rate_limited".equals(e.type())) {
                response.setHeader("Retry-After", "1");
            }
            errors.write(e, request, response);
            return;
        }
        chain.doFilter(request, response);
    }

    private Caller authenticate(HttpServletRequest request, String ip) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            throw ApiException.unauthenticated();
        }
        String key = header.substring("Bearer ".length()).trim();
        String modeFromKey;
        if (key.startsWith("sk_test_")) {
            modeFromKey = "test";
        } else if (key.startsWith("sk_live_")) {
            modeFromKey = "live";
        } else {
            throw ApiException.unauthenticated();   // public keys can't use the Payment API
        }

        List<Caller> found = db.query("""
                SELECT k.id, k.merchant_id, k.mode, m.status
                  FROM api_keys k JOIN merchants m ON m.id = k.merchant_id
                 WHERE k.key_hash = ? AND k.type = 'secret' AND k.status = 'ACTIVE'
                """,
                (rs, i) -> new Caller(rs.getString("merchant_id"), rs.getString("mode"),
                        rs.getString("id"), rs.getString("status"), ip),
                sha256(key));
        if (found.isEmpty() || !found.get(0).mode().equals(modeFromKey)) {
            throw ApiException.unauthenticated();
        }
        Caller caller = found.get(0);
        if ("CLOSED".equals(caller.merchantStatus())) {
            throw ApiException.forbidden("merchant_closed", "This account is closed.");
        }
        if ("live".equals(caller.mode()) && !"LIVE".equals(caller.merchantStatus())
                && !"SUSPENDED".equals(caller.merchantStatus())) {
            throw ApiException.forbidden("merchant_not_live",
                    "Live keys work only after the account is approved. Use your test key until then.");
        }
        return caller;
    }

    private void checkRateLimits(Caller caller, HttpServletRequest request, HttpServletResponse response) {
        long now = System.nanoTime();
        TokenBucket perKey = rateLimiter.perKey(caller.keyId());
        boolean allowed = perKey.tryTake(now);
        if (allowed && "POST".equals(request.getMethod()) && request.getRequestURI().endsWith("/payment-sessions")) {
            allowed = rateLimiter.createSession(caller.keyId()).tryTake(now);
        }
        response.setHeader("RateLimit-Limit", String.valueOf(perKey.capacity()));
        response.setHeader("RateLimit-Remaining", String.valueOf(perKey.remaining(now)));
        response.setHeader("RateLimit-Reset", "1");
        if (!allowed) {
            throw ApiException.rateLimited();
        }
    }

    public static String sha256(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
