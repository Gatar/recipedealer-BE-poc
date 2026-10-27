package com.gatar.recipedealer.auth;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.gatar.recipedealer.web.ApiException;

import io.github.bucket4j.Bucket;

/**
 * Limits failed password and registration-code attempts: 5 per key (user name, or registration) within 15 minutes,
 * and 50 in total within an hour. While a limit is exhausted, attempts answer 429 without checking the password.
 */
@Component
public class LoginRateLimiter {

    static final int MAX_FAILURES_PER_KEY = 5;
    static final int MAX_FAILURES_TOTAL = 50;
    /** Random user names must not grow the map without bound; forgetting all keys only resets their budgets. */
    private static final int MAX_KEYS = 10_000;

    private final Map<String, Bucket> perKey = new ConcurrentHashMap<>();
    private volatile Bucket total = totalBucket();

    private static Bucket keyBucket() {
        return Bucket.builder()
                .addLimit(limit -> limit.capacity(MAX_FAILURES_PER_KEY).refillGreedy(MAX_FAILURES_PER_KEY, Duration.ofMinutes(15)))
                .build();
    }

    private static Bucket totalBucket() {
        return Bucket.builder()
                .addLimit(limit -> limit.capacity(MAX_FAILURES_TOTAL).refillGreedy(MAX_FAILURES_TOTAL, Duration.ofHours(1)))
                .build();
    }

    /** Throws 429 while the key or the total budget is exhausted. */
    public void check(String key) {
        Bucket bucket = perKey.get(key);
        if (total.getAvailableTokens() <= 0 || (bucket != null && bucket.getAvailableTokens() <= 0)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "too_many_requests",
                    "too many failed attempts, try again later");
        }
    }

    /** Records a failed attempt for the key. */
    public void failure(String key) {
        if (perKey.size() >= MAX_KEYS) {
            perKey.clear();
        }
        perKey.computeIfAbsent(key, k -> keyBucket()).tryConsume(1);
        total.tryConsume(1);
    }

    /** For tests. */
    public void reset() {
        perKey.clear();
        total = totalBucket();
    }
}
