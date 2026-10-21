package com.gatar.recipedealer.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Admin credentials from the environment ({@code RECIPEDEALER_ADMIN_USERNAME},
 * {@code RECIPEDEALER_ADMIN_PASSWORD_HASH} as bcrypt). Without a hash all admin endpoints answer 401.
 */
@ConfigurationProperties("recipedealer.admin")
public record AdminProperties(String username, String passwordHash) {

    public boolean configured() {
        return username != null && !username.isBlank() && passwordHash != null && !passwordHash.isBlank();
    }
}
