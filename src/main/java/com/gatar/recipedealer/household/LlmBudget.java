package com.gatar.recipedealer.household;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Monthly Claude API budget of a household ({@code household.llm_budget_usd}, null = no limit). Spending is the cost
 * of the household's extraction runs (admin comparisons excluded), taggings and translations since the start of the
 * current calendar month (UTC, database clock). A call starts only while spending is below the budget, so the last
 * call of a month may go a little over it.
 */
@Component
public class LlmBudget {

    private static final String MONTH = "date_trunc('month', now() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC'";

    private final JdbcClient jdbc;

    public LlmBudget(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** USD spent this month. */
    public BigDecimal spent(UUID householdId) {
        return jdbc.sql("""
                        SELECT coalesce((SELECT sum(r.cost_usd) FROM processing_run r JOIN capture c ON c.id = r.capture_id
                                         WHERE c.household_id = :household AND r.purpose <> 'comparison'
                                           AND r.started_at >= %1$s), 0)
                             + coalesce((SELECT sum(t.cost_usd) FROM auto_tagging t JOIN capture c ON c.id = t.capture_id
                                         WHERE c.household_id = :household AND t.created_at >= %1$s), 0)
                             + coalesce((SELECT sum(t.cost_usd) FROM auto_translation t JOIN capture c ON c.id = t.capture_id
                                         WHERE c.household_id = :household AND t.created_at >= %1$s), 0)"""
                        .formatted(MONTH))
                .param("household", householdId).query(BigDecimal.class).single();
    }

    /** The household's monthly budget in USD, or null for no limit. */
    public BigDecimal budget(UUID householdId) {
        return jdbc.sql("SELECT llm_budget_usd FROM household WHERE id = :id").param("id", householdId)
                .query((rs, n) -> rs.getBigDecimal("llm_budget_usd")).list().getFirst();
    }

    /** Whether the household may not start another Claude call this month. */
    public boolean exhausted(UUID householdId) {
        BigDecimal budget = budget(householdId);
        return budget != null && spent(householdId).compareTo(budget) >= 0;
    }

    public boolean exhaustedForCapture(UUID captureId) {
        return exhausted(jdbc.sql("SELECT household_id FROM capture WHERE id = :id").param("id", captureId)
                .query(UUID.class).single());
    }
}
