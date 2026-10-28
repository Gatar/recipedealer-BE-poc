package com.gatar.recipedealer.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import com.gatar.recipedealer.blob.FileSystemBlobStore;
import com.gatar.recipedealer.processing.Capture;
import com.gatar.recipedealer.processing.ExtractionResult;
import com.gatar.recipedealer.recipe.RecipeContent;

import tools.jackson.databind.json.JsonMapper;

/**
 * Against the real Claude API; opt-in ({@code CLAUDE_IT=true} and {@code ANTHROPIC_API_KEY}), never in CI. Costs a
 * fraction of a cent.
 */
@EnabledIfEnvironmentVariable(named = "CLAUDE_IT", matches = "true")
class ClaudeApiTest {

    static final LlmProperties.Step HAIKU = new LlmProperties.Step("haiku", "claude-haiku-5-5", "medium");

    @TempDir
    Path dir;

    private static LlmProperties properties(String key) {
        return new LlmProperties(key, Duration.ofSeconds(120), 1, 16000, 30_000_000, 2000, 400_000, List.of(HAIKU), Map.of());
    }

    @Test
    void haikuExtractsAPolishRecipeFromText() throws Exception {
        var properties = properties(System.getenv("ANTHROPIC_API_KEY"));
        var blobs = new FileSystemBlobStore(dir);
        var inputs = new LlmInputBuilder(blobs, properties);
        var extractor = new ClaudeExtractor(HAIKU, AnthropicClaudeClient.create(properties, null), inputs,
                new DishPhotoCropper(blobs, inputs));
        var capture = new Capture(UUID.randomUUID(), Instant.now(), UUID.randomUUID(), UUID.randomUUID(), 1,
                "0".repeat(64), JsonMapper.builder().build().readTree("""
                        {"source": {"type": "TEXT"}, "raw": {"text": "Racuchy babci Zosi\\n\
                        Składniki: 2 szklanki mąki, 1 szklanka kefiru, 2 jajka, 2 jabłka, szczypta soli.\\n\
                        Jabłka obrać i pokroić w kostkę. Wymieszać mąkę, kefir i jajka, dodać jabłka. \
                        Smażyć na rozgrzanym oleju z obu stron na złoty kolor."}}"""), List.of());

        ExtractionResult result = extractor.extract(capture);

        assertThat(result.problems()).isEmpty();
        var recipe = result.candidates().getFirst().recipe();
        assertThat(RecipeContent.title(recipe)).containsIgnoringCase("racuchy");
        assertThat(RecipeContent.ingredientLines(recipe)).anySatisfy(line -> assertThat(line).contains("kefir"));
        assertThat(RecipeContent.stepLines(recipe)).isNotEmpty();
        assertThat(result.usage().inputTokens() + result.usage().cacheCreationInputTokens()
                + result.usage().cacheReadInputTokens()).isPositive();
        assertThat(result.usage().model()).isEqualTo("claude-haiku-5-5");
    }

    @Test
    void invalidKeyFailsAsUnavailable() throws IOException {
        var client = AnthropicClaudeClient.create(properties("sk-ant-api03-invalid-key-for-recipedealer-test"), null);

        assertThatThrownBy(() -> client.ping("claude-haiku-5-5")).isInstanceOfSatisfying(LlmException.class, e -> {
            assertThat(e.kind()).isEqualTo(LlmException.Kind.UNAVAILABLE);
            assertThat(e.httpStatus()).isEqualTo(401);
            assertThat(e.errorType()).isEqualTo("authentication_error");
        });
    }
}
