package com.gatar.recipedealer.auth;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.gatar.recipedealer.web.ApiError;

import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Global limit on failed admin authentications: after 5 failures within an hour every admin request gets 429
 * until the bucket refills (one attempt every 12 minutes).
 */
@Component
public class AdminAuthRateLimiter {

    static final int MAX_FAILURES_PER_HOUR = 5;

    private volatile Bucket failures = newBucket();

    private static Bucket newBucket() {
        return Bucket.builder()
                .addLimit(limit -> limit.capacity(MAX_FAILURES_PER_HOUR).refillGreedy(MAX_FAILURES_PER_HOUR, Duration.ofHours(1)))
                .build();
    }

    /** Rejects admin requests with 429 while the failure budget is exhausted. Runs before Basic authentication. */
    public OncePerRequestFilter blockingFilter() {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                if (failures.getAvailableTokens() > 0) {
                    chain.doFilter(request, response);
                    return;
                }
                long waitNanos = failures.estimateAbilityToConsume(1).getNanosToWaitForRefill();
                response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(Math.max(1, TimeUnit.NANOSECONDS.toSeconds(waitNanos))));
                write(response, HttpStatus.TOO_MANY_REQUESTS, ApiError.of("too_many_requests", "too many failed admin logins"));
            }
        };
    }

    /** Answers 401 for a failed or missing admin login and counts it as a failure. */
    public AuthenticationEntryPoint countingEntryPoint() {
        return (request, response, e) -> {
            failures.tryConsume(1);
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"recipedealer-admin\"");
            write(response, HttpStatus.UNAUTHORIZED, ApiError.of("unauthorized", "admin credentials required"));
        };
    }

    /** For tests. */
    public void reset() {
        failures = newBucket();
    }

    static void write(HttpServletResponse response, HttpStatus status, ApiError error) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(error.toJson());
    }
}
