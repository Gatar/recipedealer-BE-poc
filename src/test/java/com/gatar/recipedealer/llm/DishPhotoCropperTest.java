package com.gatar.recipedealer.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.gatar.recipedealer.llm.LlmInputBuilder.Region;

class DishPhotoCropperTest {

    private static final Region FIRST = new Region(106, 45, 1054, 748);

    @Test
    void secondLookMayShrinkOrWidenTheFirstBox() {
        assertThat(DishPhotoCropper.plausibleRefinement(new Region(0, 0, 1000, 600), new Region(0, 0, 1000, 400))).isTrue();
        assertThat(DishPhotoCropper.plausibleRefinement(new Region(845, 136, 485, 854), new Region(610, 36, 960, 1164))).isTrue();
    }

    @Test
    void secondLookFarFromTheFirstBoxIsIgnored() {
        assertThat(DishPhotoCropper.plausibleRefinement(FIRST, new Region(106, 400, 1034, 34))).isFalse();
        assertThat(DishPhotoCropper.plausibleRefinement(FIRST, new Region(106, 900, 1054, 700))).isFalse();
    }
}
