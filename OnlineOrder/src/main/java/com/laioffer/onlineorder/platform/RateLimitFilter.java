package com.laioffer.onlineorder.platform;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Limits the endpoints worth abusing: password guessing on {@code /login} (per client address and
 * per account, so a botnet spread over many addresses still cannot hammer one account), account
 * creation on {@code /signup}, and checkout, which takes row locks on stock.
 *
 * <p>Runs before Spring Security, so rejected requests cost no password hash. Rejections are
 * 429 with {@code Retry-After}. The client address is {@code getRemoteAddr()}: behind a load
 * balancer set {@code server.forward-headers-strategy=native} so it is the real client, never
 * trust a forwarded header from the internet directly.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class RateLimitFilter extends OncePerRequestFilter {

    record Rule(String name, String method, String path, RateLimiter limiter, boolean perAccount) {
    }

    private final boolean enabled;
    private final MeterRegistry metrics;
    private final List<Rule> rules = new ArrayList<>();

    public RateLimitFilter(@Value("${rate-limit.enabled:true}") boolean enabled,
                           @Value("${rate-limit.login-per-minute:10}") int loginPerMinute,
                           @Value("${rate-limit.login-per-account-per-minute:20}") int loginPerAccountPerMinute,
                           @Value("${rate-limit.signup-per-minute:5}") int signupPerMinute,
                           @Value("${rate-limit.checkout-per-minute:30}") int checkoutPerMinute,
                           MeterRegistry metrics) {
        this.enabled = enabled;
        this.metrics = metrics;
        long minute = Duration.ofMinutes(1).toNanos();
        rules.add(new Rule("login", "POST", "/login", new RateLimiter(loginPerMinute, minute, System::nanoTime), false));
        rules.add(new Rule("login-account", "POST", "/login",
                new RateLimiter(loginPerAccountPerMinute, minute, System::nanoTime), true));
        rules.add(new Rule("signup", "POST", "/signup", new RateLimiter(signupPerMinute, minute, System::nanoTime), false));
        rules.add(new Rule("checkout", "POST", "/orders", new RateLimiter(checkoutPerMinute, minute, System::nanoTime), false));
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !enabled;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        for (Rule rule : rules) {
            if (!rule.method().equals(request.getMethod()) || !rule.path().equals(request.getServletPath())) {
                continue;
            }
            String key = rule.perAccount() ? account(request) : request.getRemoteAddr();
            if (key == null) {
                continue;
            }
            RateLimiter.Decision d = rule.limiter().tryAcquire(key);
            if (!d.allowed()) {
                metrics.counter("http.rate.limited", "rule", rule.name()).increment();
                response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                response.setHeader("Retry-After", Long.toString(d.retryAfterSeconds()));
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.getWriter().write("{\"error\":\"RATE_LIMITED\",\"message\":\"too many requests, retry in "
                        + d.retryAfterSeconds() + " s\"}");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private static String account(HttpServletRequest request) {
        String username = request.getParameter("username");
        return username == null || username.isBlank() ? null : username.trim().toLowerCase(Locale.ROOT);
    }
}
