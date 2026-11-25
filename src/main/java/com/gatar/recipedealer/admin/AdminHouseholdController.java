package com.gatar.recipedealer.admin;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.gatar.recipedealer.common.Jdbc;
import com.gatar.recipedealer.household.HouseholdService;
import com.gatar.recipedealer.household.LlmBudget;
import com.gatar.recipedealer.web.ApiException;
import com.gatar.recipedealer.web.NotFoundException;

import tools.jackson.databind.JsonNode;

/**
 * All households with their counts; the admin creates, renames and deletes them, sets their limits and hands out
 * invitation codes.
 */
@RestController
@RequestMapping("/v1/admin/households")
public class AdminHouseholdController {

    private final JdbcClient jdbc;
    private final LlmBudget budget;
    private final HouseholdService households;

    public AdminHouseholdController(JdbcClient jdbc, LlmBudget budget, HouseholdService households) {
        this.jdbc = jdbc;
        this.budget = budget;
        this.households = households;
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
            jdbc.sql("UPDATE household SET name = :name WHERE id = :id")
                    .param("name", name(body.get("name"))).param("id", householdId).update();
        }
        if (body.has("recipeLimit")) {
            jdbc.sql("UPDATE household SET recipe_limit = :limit WHERE id = :id")
                    .param("limit", recipeLimit(body.get("recipeLimit"))).param("id", householdId).update();
        }
        if (body.has("llmBudgetUsd")) {
            jdbc.sql("UPDATE household SET llm_budget_usd = CAST(:budget AS numeric) WHERE id = :id")
                    .param("budget", budgetUsd(body.get("llmBudgetUsd"))).param("id", householdId).update();
        }
        return query(householdId).getFirst();
    }

    /**
     * A new household without users, beyond the household limit. Body: {@code name}, optional {@code recipeLimit} and
     * {@code llmBudgetUsd} (missing: the defaults of a household created in the app; null: no limit).
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AdminHousehold create(@RequestBody JsonNode body) {
        if (body == null || !body.isObject() || !body.has("name")) {
            throw ApiException.badRequest("name is required");
        }
        var defaults = households.properties();
        UUID id = households.createHouseholdAsAdmin(name(body.get("name")),
                body.has("recipeLimit") ? recipeLimit(body.get("recipeLimit")) : Integer.valueOf(defaults.defaultRecipeLimit()),
                body.has("llmBudgetUsd") ? budgetUsd(body.get("llmBudgetUsd")) : defaults.defaultLlmBudgetUsd());
        return query(id).getFirst();
    }

    /** Deletes a household with all its users, like its owner would (never the default household). */
    @DeleteMapping("/{householdId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID householdId) {
        households.deleteHousehold(householdId);
    }

    /** A single-use invitation code to a household (48 h); the code is shown only in this answer. */
    @PostMapping("/{householdId}/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    public HouseholdService.InvitationView invite(@PathVariable UUID householdId) {
        return households.createInvitation(householdId, null, "admin");
    }

    @GetMapping("/{householdId}/invitations")
    public List<HouseholdService.InvitationView> invitations(@PathVariable UUID householdId) {
        if (query(householdId).isEmpty()) {
            throw new NotFoundException("household " + householdId + " not found");
        }
        return households.invitations(householdId);
    }

    @DeleteMapping("/{householdId}/invitations/{invitationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeInvitation(@PathVariable UUID householdId, @PathVariable UUID invitationId) {
        households.revokeInvitation(householdId, invitationId);
    }

    private static String name(JsonNode name) {
        if (name == null || !name.isString() || name.asString().isBlank() || name.asString().strip().length() > 100) {
            throw ApiException.badRequest("name must be 1-100 characters");
        }
        return name.asString().strip();
    }

    private static Integer recipeLimit(JsonNode limit) {
        if (!limit.isNull() && (!limit.isIntegralNumber() || limit.asInt() < 0)) {
            throw ApiException.badRequest("recipeLimit must be a number >= 0 or null");
        }
        return limit.isNull() ? null : limit.asInt();
    }

    private static BigDecimal budgetUsd(JsonNode budget) {
        if (!budget.isNull() && (!budget.isNumber() || budget.decimalValue().signum() < 0
                || budget.decimalValue().compareTo(new BigDecimal("99999999")) > 0)) {
            throw ApiException.badRequest("llmBudgetUsd must be a number >= 0 or null");
        }
        return budget.isNull() ? null : budget.decimalValue().setScale(2, RoundingMode.HALF_UP);
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
