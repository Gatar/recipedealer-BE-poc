package com.gatar.recipedealer.auth;

import java.util.UUID;

/** The authenticated caller of the device API: one enrolled device of one user. */
public record DevicePrincipal(UUID userId, String userName, UUID deviceId, String deviceName) {
}
