package dev.yolbert.auth_service.config;

import dev.yolbert.auth_service.dto.ApiErrorResponse;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory per-IP rate limiter for {@code POST /auth/login}.
 *
 * <p>Keeps a sliding 60-second window of request timestamps per client IP and
 * rejects requests over the threshold with 429. Only valid on a single node;
 * a shared store (Redis + Bucket4j) is out of scope for this delivery.
 */
public class LoginRateLimitFilter implements Filter {

    static final int MAX_REQUESTS_PER_MINUTE = 10;
    static final long WINDOW_MILLIS = 60_000L;
    static final String LOGIN_PATH = "/auth/login";

    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, Deque<Long>> requestLog = new ConcurrentHashMap<>();

    public LoginRateLimitFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (isLoginRequest(request)) {
            String ip = request.getRemoteAddr();
            if (isRateLimited(ip)) {
                writeTooManyRequests(response);
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private boolean isLoginRequest(ServletRequest request) {
        if (!(request instanceof HttpServletRequest httpRequest)) {
            return false;
        }
        return "POST".equalsIgnoreCase(httpRequest.getMethod())
                && LOGIN_PATH.equals(httpRequest.getRequestURI());
    }

    private boolean isRateLimited(String ip) {
        long now = System.currentTimeMillis();
        boolean[] blocked = {false};
        requestLog.compute(ip, (key, existing) -> {
            Deque<Long> timestamps = existing != null ? existing : new ArrayDeque<>();
            timestamps.removeIf(ts -> ts < now - WINDOW_MILLIS);
            if (timestamps.size() >= MAX_REQUESTS_PER_MINUTE) {
                blocked[0] = true;
                return timestamps;
            }
            timestamps.addLast(now);
            return timestamps;
        });
        return blocked[0];
    }

    private void writeTooManyRequests(ServletResponse response) throws IOException {
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        httpResponse.setStatus(429);
        httpResponse.setContentType("application/json");
        httpResponse.setCharacterEncoding("UTF-8");
        ApiErrorResponse body = ApiErrorResponse.builder()
                .message("Demasiadas peticiones. Intenta de nuevo más tarde.")
                .build();
        objectMapper.writeValue(httpResponse.getWriter(), body);
    }

    /** Test hook: clears the in-memory window across test methods. */
    public void reset() {
        requestLog.clear();
    }
}