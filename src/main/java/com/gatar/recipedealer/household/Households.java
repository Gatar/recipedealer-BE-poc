package com.gatar.recipedealer.household;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.gatar.recipedealer.web.NotFoundException;

/**
 * Household ownership of captures and recipes. A device sees only its own household's data: an id of another
 * household is answered like an unknown id (404), so ids of other households cannot be probed.
 */
@Component
public class Households {

    /** The household everything belonged to before households existed (V19); admin enrollment adds users to it. */
    public static final UUID DEFAULT_ID = UUID.fromString("00000000-0000-7000-8000-000000000001");

    private final JdbcClient jdbc;

    public Households(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 404 unless the capture exists and belongs to the household. */
    public void requireCapture(UUID householdId, UUID captureId) {
        if (!owns("capture", householdId, captureId)) {
            throw new NotFoundException("capture " + captureId + " not found");
        }
    }

    /** 404 unless the recipe exists (also deleted or merged) and belongs to the household. */
    public void requireRecipe(UUID householdId, UUID recipeId) {
        if (!hasRecipe(householdId, recipeId)) {
            throw new NotFoundException("recipe " + recipeId + " not found");
        }
    }

    /** Whether the recipe exists (also deleted or merged) and belongs to the household. */
    public boolean hasRecipe(UUID householdId, UUID recipeId) {
        return owns("recipe", householdId, recipeId);
    }

    public UUID ofCapture(UUID captureId) {
        return jdbc.sql("SELECT household_id FROM capture WHERE id = :id").param("id", captureId)
                .query(UUID.class).single();
    }

    public UUID ofRecipe(UUID recipeId) {
        return jdbc.sql("SELECT household_id FROM recipe WHERE id = :id").param("id", recipeId)
                .query(UUID.class).single();
    }

    private boolean owns(String table, UUID householdId, UUID id) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM " + table + " WHERE id = :id AND household_id = :household)")
                .param("id", id).param("household", householdId).query(Boolean.class).single();
    }
}
