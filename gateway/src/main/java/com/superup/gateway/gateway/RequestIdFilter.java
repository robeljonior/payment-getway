package com.superup.gateway.gateway;

import com.superup.gateway.common.Ids;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request an ID (X-Request-Id), so one payment can be followed through every layer,
 * the logs and the audit log. It also sets X-Api-Version.
 */
public class RequestIdFilter extends OncePerRequestFilter {

    private static final String ATTRIBUTE = "gateway.requestId";

    public static String requestId(HttpServletRequest request) {
        Object id = request.getAttribute(ATTRIBUTE);
        return id == null ? "unknown" : id.toString();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = Ids.newId(Ids.REQUEST);
        request.setAttribute(ATTRIBUTE, id);
        response.setHeader("X-Request-Id", id);
        response.setHeader("X-Api-Version", "1.0");
        MDC.put("requestId", id);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove("requestId");
        }
    }
}
