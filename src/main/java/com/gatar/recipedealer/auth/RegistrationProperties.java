package com.gatar.recipedealer.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Self-service registration: {@code code} is the household's registration code ({@code REGISTRATION_CODE}); without
 * it registration is disabled. {@code maxUsers} caps all users, including those enrolled by the admin (the admin
 * may still enroll beyond it).
 */
@ConfigurationProperties("recipedealer.registration")
public record RegistrationProperties(String code, @DefaultValue("10") int maxUsers) {

    public boolean enabled() {
        return code != null && !code.isBlank();
    }
}
