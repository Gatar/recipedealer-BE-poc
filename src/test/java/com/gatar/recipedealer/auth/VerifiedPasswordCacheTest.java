package com.gatar.recipedealer.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

class VerifiedPasswordCacheTest {

    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder(4);
    private final String hash = bcrypt.encode("sekret");
    private final AtomicInteger checks = new AtomicInteger();
    private Instant now = Instant.parse("2026-11-25T20:00:00Z");

    private final PasswordEncoder counting = new PasswordEncoder() {
        @Override
        public String encode(CharSequence raw) {
            return bcrypt.encode(raw);
        }

        @Override
        public boolean matches(CharSequence raw, String encoded) {
            checks.incrementAndGet();
            return bcrypt.matches(raw, encoded);
        }
    };

    private final VerifiedPasswordCache cache = new VerifiedPasswordCache(counting, new Clock() {
        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    });

    @Test
    void aVerifiedPasswordIsNotCheckedAgainUntilItExpires() {
        assertThat(cache.matches("sekret", hash)).isTrue();
        assertThat(cache.matches("sekret", hash)).isTrue();
        assertThat(cache.matches("sekret", hash)).isTrue();
        assertThat(checks).hasValue(1);

        now = now.plus(VerifiedPasswordCache.TTL);
        assertThat(cache.matches("sekret", hash)).isTrue();
        assertThat(checks).hasValue(2);
    }

    @Test
    void aWrongPasswordIsAlwaysChecked() {
        assertThat(cache.matches("sekret", hash)).isTrue();
        assertThat(cache.matches("zle", hash)).isFalse();
        assertThat(cache.matches("zle", hash)).isFalse();
        assertThat(checks).hasValue(3);
        assertThat(cache.size()).isEqualTo(1);
    }

    @Test
    void aChangedHashNeedsANewCheck() {
        assertThat(cache.matches("sekret", hash)).isTrue();
        String newHash = bcrypt.encode("sekret");
        assertThat(cache.matches("sekret", newHash)).isTrue();
        assertThat(checks).hasValue(2);
    }
}
