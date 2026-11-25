package com.gatar.recipedealer.household;

import java.math.BigDecimal;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code maxHouseholds}: households that are not deleted, the default one included; {@code defaultRecipeLimit}: the
 * recipe limit a new household starts with (the admin changes it per household); {@code maxNewPerHour}: households
 * created through the API per hour, across all callers; {@code invitationTtl}: how long an invitation code works;
 * {@code defaultLlmBudgetUsd}: the monthly Claude budget a new household starts with ({@link LlmBudget}).
 */
@ConfigurationProperties("recipedealer.households")
public record HouseholdProperties(@DefaultValue("20") int maxHouseholds, @DefaultValue("40") int defaultRecipeLimit,
                                  @DefaultValue("10") int maxNewPerHour, @DefaultValue("48h") Duration invitationTtl,
                                  @DefaultValue("1.00") BigDecimal defaultLlmBudgetUsd) {
}
