package com.example.stock.congfig;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RateLimitingFilter implements Filter {

    // Maximum 60 requests per minute per IP address
    private static final int MAX_REQUESTS_PER_MINUTE = 60;
    private final Map<String, RequestTracker> clientRequests = new ConcurrentHashMap<>();

    private static class RequestTracker {
        long windowStart = System.currentTimeMillis();
        AtomicInteger count = new AtomicInteger(0);

        synchronized void resetIfExpired() {
            long now = System.currentTimeMillis();
            if (now - windowStart > 60_000L) {
                windowStart = now;
                count.set(0);
            }
        }
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;

        // Only rate limit analysis requests
        String path = req.getRequestURI();
        if (path != null && path.contains("/analyze")) {
            String clientIp = getClientIp(req);
            RequestTracker tracker = clientRequests.computeIfAbsent(clientIp, k -> new RequestTracker());
            tracker.resetIfExpired();

            int currentRequests = tracker.count.incrementAndGet();
            if (currentRequests > MAX_REQUESTS_PER_MINUTE) {
                log.warn("Rate limit exceeded for IP: {} on {}", clientIp, path);
                res.setStatus(429);
                res.setContentType("application/json");
                res.getWriter().write("{\"error\": \"Rate limit exceeded. Maximum 60 requests per minute allowed.\"}");
                return;
            }
        }

        chain.doFilter(request, response);
    }

    private String getClientIp(HttpServletRequest request) {
        String xf = request.getHeader("X-Forwarded-For");
        if (xf != null && !xf.isBlank()) {
            return xf.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
