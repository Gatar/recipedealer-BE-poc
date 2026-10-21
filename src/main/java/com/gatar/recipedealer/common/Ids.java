package com.gatar.recipedealer.common;

import java.util.UUID;

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;

/** Server-generated identifiers: time-ordered UUIDv7. */
public final class Ids {

    private static final TimeBasedEpochGenerator GENERATOR = Generators.timeBasedEpochGenerator();

    private Ids() {
    }

    public static UUID newId() {
        return GENERATOR.generate();
    }
}
