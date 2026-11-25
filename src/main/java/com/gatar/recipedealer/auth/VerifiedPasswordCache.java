package com.gatar.recipedealer.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.security.crypto.password.PasswordEncoder;

import com.gatar.recipedealer.common.Hashing;

/**
 * Admin logins: HTTP Basic sends the password with every request, and a bcrypt check (cost 14 on the server, about a
 * second) on each would make the dashboard crawl, one check per thumbnail. A password that matched is remembered (as
 * SHA-256 of password and hash, never the password) for {@link #TTL}; only matches are cached, so a wrong password is
 * always checked with bcrypt and counted by the rate limiter.
 */
class VerifiedPasswordCache implements PasswordEncoder {

    static final Duration TTL = Duration.ofMinutes(15);
    private static final int MAX_ENTRIES = 100;

    private final PasswordEncoder delegate;
    private final Clock clock;
    private final Map<String, Instant> verified = new ConcurrentHashMap<>();

    VerifiedPasswordCache(PasswordEncoder delegate, Clock clock) {
        this.delegate = delegate;
        this.clock = clock;
    }

    @Override
    public String encode(CharSequence rawPassword) {
        return delegate.encode(rawPassword);
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        if (rawPassword == null || encodedPassword == null) {
            return delegate.matches(rawPassword, encodedPassword);
        }
        String key = HexFormat.of().formatHex(Hashing.sha256(rawPassword + "\n" + encodedPassword));
        Instant now = clock.instant();
        Instant until = verified.get(key);
        if (until != null && now.isBefore(until)) {
            return true;
        }
        boolean matches = delegate.matches(rawPassword, encodedPassword);
        if (matches) {
            if (verified.size() >= MAX_ENTRIES) {
                verified.clear();
            }
            verified.put(key, now.plus(TTL));
        } else {
            verified.remove(key);
        }
        return matches;
    }

    @Override
    public boolean upgradeEncoding(String encodedPassword) {
        return false;
    }

    int size() {
        return verified.size();
    }
}
