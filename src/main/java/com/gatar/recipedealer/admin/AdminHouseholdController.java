package com.gatar.recipedealer.admin;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.gatar.recipedealer.common.Jdbc;
import com.gatar.recipedealer.household.LlmBudget;
import com.gatar.recipedealer.web.ApiException;
import com.gatar.recipedealer.web.NotFoundException;

import tools.jackson.databind.JsonNode;

/** All households with their counts; the admin renames them and sets their recipe limit. */
@RestController
@RequestMapping("/v1/admin/households")
public class AdminHouseholdController {

    private final JdbcClient jdbc;
    private final LlmBudget budget;

    public AdminHouseholdController(JdbcClient jdbc, LlmBudget budget) {
        this.jdbc = jdbc;
        this.budget = budget;
    }

    /**
     * {@code recipeLimit}, {@code llmBudgetUsd} null = no limit; {@code llmSpentUsd}: this calendar month (UTC);
     * counts are of live users, devices and recipes.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AdminHousehold(UUID householdId, String name, Integer recipeLimit, BigDecimal llmBudgetUsd,
                                 BigDecimal llmSpentUsd, int users, int devices, int recipes, int captures,
                                 Instant createdAt, Instant deletedAt) {
    }

    @GetMapping
    public List<AdminHousehold> households() {
        return query(null);
    }

    /**
     * Body: any of {@code name}, {@code recipeLimit} and {@code llmBudgetUsd} (numbers, or null for no limit); a field
     * that is missing stays as it is.
     */
    @PatchMapping("/{householdId}")
    @Transactional
    public AdminHousehold update(@PathVariable UUID householdId, @RequestBody JsonNode body) {
        if (body == null || !body.isObject()) {
            throw ApiException.badRequest("body must be an object");
        }
        if (query(householdId).isEmpty()) {
            throw new NotFoundException("household " + householdId + " not found");
        }
        if (body.has("name")) {
            JsonNode name = body.get("name");
            if (!name.isString() || name.asString().isBlank() || name.asString().strip().length() > 100) {
                throw ApiException.badRequest("name must be 1-100 characters");
            }
            jdbc.sql("UPDATE household SET name = :name WHERE id = :id")
                    .param("name", name.asString().strip()).param("id", householdId).update();
        }
        if (body.has("recipeLimit")) {
            JsonNode limit = body.get("recipeLimit");
            if (!limit.isNull() && (!limit.isIntegralNumber() || limit.asInt() < 0)) {
                throw ApiException.badRequest("recipeLimit must be a number >= 0 or null");
            }
            jdbc.sql("UPDATE household SET recipe_limit = :limit WHERE id = :id")
                    .param("limit", limit.isNull() ? null : limit.asInt()).param("id", householdId).update();
        }
        if (body.has("llmBudgetUsd")) {
            JsonNode budget = body.get("llmBudgetUsd");
            if (!budget.isNull() && (!budget.isNumber() || budget.decimalValue().signum() < 0
                    || budget.decimalValue().compareTo(new BigDecimal("99999999")) > 0)) {
                throw ApiException.badRequest("llmBudgetUsd must be a number >= 0 or null");
            }
            jdbc.sql("UPDATE household SET llm_budget_usd = CAST(:budget AS numeric) WHERE id = :id")
                    .param("budget", budget.isNull() ? null : budget.decimalValue().setScale(2, RoundingMode.HALF_UP))
                    .param("id", householdId).update();
        }
        return query(householdId).getFirst();
    }

    private List<AdminHousehold> query(UUID householdId) {
        return jdbc.sql("""
                        SELECT h.id, h.name, h.recipe_limit, h.llm_budget_usd, h.created_at, h.deleted_at,
                               (SELECT count(*) FROM app_user u WHERE u.household_id = h.id AND u.deleted_at IS NULL) AS users,
                               (SELECT count(*) FROM device_token d JOIN app_user u ON u.id = d.user_id
                                WHERE u.household_id = h.id AND u.deleted_at IS NULL AND d.revoked_at IS NULL) AS devices,
                               (SELECT count(*) FROM recipe r WHERE r.household_id = h.id
                                  AND r.deleted_at IS NULL AND r.merged_into IS NULL) AS recipes,
                               (SELECT count(*) FROM capture c WHERE c.household_id = h.id) AS captures
                        FROM household h
                        WHERE CAST(:id AS uuid) IS NULL OR h.id = :id
                        ORDER BY h.created_at""")
                .param("id", householdId)
                .query((rs, n) -> new AdminHousehold(Jdbc.uuid(rs, "id"), rs.getString("name"),
                        (Integer) rs.getObject("recipe_limit"), rs.getBigDecimal("llm_budget_usd"),
                        budget.spent(Jdbc.uuid(rs, "id")), rs.getInt("users"), rs.getInt("devices"),
                        rs.getInt("recipes"), rs.getInt("captures"), Jdbc.instant(rs, "created_at"),
                        Jdbc.instant(rs, "deleted_at")))
                .list();
    }
}
