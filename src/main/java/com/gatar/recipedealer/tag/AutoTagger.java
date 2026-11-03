package com.gatar.recipedealer.tag;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.gatar.recipedealer.llm.ClaudeClient;
import com.gatar.recipedealer.llm.LlmException;
import com.gatar.recipedealer.llm.LlmPricing;
import com.gatar.recipedealer.llm.TagPrompt;
import com.gatar.recipedealer.recipe.ResolutionService;
import com.gatar.recipedealer.tag.AutoTagRepository.Outcome;
import com.gatar.recipedealer.tag.AutoTagRepository.Pending;
import com.gatar.recipedealer.tag.AutoTagRepository.Status;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The tagging job of a capture: one Claude call per new recipe the capture created without tags (outside any
 * transaction), then the chosen tags are assigned and the capture leaves {@code processing}. A failed call never
 * blocks the capture: the recipe just stays without tags.
 */
@Service
public class AutoTagger {

    /** Job step of the tagging job ({@code process_capture} jobs, so they count as processing in progress). */
    public static final String STEP = "tagging";

    private static final Logger log = LoggerFactory.getLogger(AutoTagger.class);

    private final AutoTagRepository taggings;
    private final TagService tags;
    private final ResolutionService resolutions;
    private final ClaudeClient client;
    private final LlmPricing pricing;
    private final AutoTagProperties properties;
    private final JdbcClient jdbc;
    private final JsonMapper jsonMapper;
    private final TransactionTemplate tx;

    public AutoTagger(AutoTagRepository taggings, TagService tags, ResolutionService resolutions, ClaudeClient client,
                      LlmPricing pricing, AutoTagProperties properties, JdbcClient jdbc, JsonMapper jsonMapper,
                      TransactionTemplate tx) {
        this.taggings = taggings;
        this.tags = tags;
        this.resolutions = resolutions;
        this.client = client;
        this.pricing = pricing;
        this.properties = properties;
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
        this.tx = tx;
    }

    public void run(UUID captureId) {
        List<Pending> pending = taggings.pending(captureId);
        List<TagPrompt.Choice> catalog = tags.catalog().categories().stream()
                .flatMap(category -> category.tags().stream()
                        .map(tag -> new TagPrompt.Choice(tag.id(), category.title(), tag.title())))
                .toList();
        List<Outcome> outcomes = new ArrayList<>();
        for (Pending tagging : pending) {
            outcomes.add(tag(tagging.recipeId(), catalog));
        }
        tx.executeWithoutResult(status -> {
            for (int i = 0; i < pending.size(); i++) {
                Outcome outcome = outcomes.get(i);
                if (outcome.status() == Status.SUCCEEDED
                        && !tags.assignAutomatically(pending.get(i).recipeId(), outcome.tags())) {
                    outcome = new Outcome(Status.SKIPPED, outcome.tags(), outcome.promptVersion(), "recipe_tagged_or_gone",
                            null, outcome.usage(), outcome.cost());
                }
                taggings.finish(pending.get(i).id(), outcome);
                log.info("Tagged recipe {} of capture {}: {} {}{}", pending.get(i).recipeId(), captureId,
                        outcome.status().name().toLowerCase(Locale.ROOT), outcome.tags(),
                        outcome.errorType() == null ? "" : " (" + outcome.errorType() + ")");
            }
            resolutions.refreshState(captureId);
        });
    }

    private Outcome tag(UUID recipeId, List<TagPrompt.Choice> catalog) {
        Optional<JsonNode> recipe = headContent(recipeId);
        if (recipe.isEmpty()) {
            return new Outcome(Status.SKIPPED, null, null, "recipe_gone", null, null, null);
        }
        if (catalog.isEmpty()) {
            return new Outcome(Status.SKIPPED, null, null, "empty_catalog", null, null, null);
        }
        List<String> ids = catalog.stream().map(TagPrompt.Choice::id).toList();
        var request = new ClaudeClient.Request(properties.model(), properties.effort(), TagPrompt.SYSTEM_PROMPT,
                TagPrompt.schema(ids), List.of(new ClaudeClient.Text(TagPrompt.input(catalog, recipe.get()))));
        ClaudeClient.Response response;
        try {
            response = client.extract(request);
        } catch (LlmException e) {
            return new Outcome(Status.FAILED, null, TagPrompt.VERSION, e.errorType(), e.getMessage(), null, null);
        } catch (RuntimeException e) {
            log.warn("Tagging recipe {} failed", recipeId, e);
            return new Outcome(Status.FAILED, null, TagPrompt.VERSION, "error", String.valueOf(e), null, null);
        }
        var cost = pricing.cost(response.usage());
        String problem = response.refusalCategory() != null ? "refusal:" + response.refusalCategory()
                : "max_tokens".equals(response.usage().stopReason()) ? "max_tokens" : null;
        if (problem != null) {
            return new Outcome(Status.FAILED, null, TagPrompt.VERSION, problem, null, response.usage(), cost);
        }
        try {
            return new Outcome(Status.SUCCEEDED, TagPrompt.parse(response.text(), ids), TagPrompt.VERSION, null, null,
                    response.usage(), cost);
        } catch (IllegalArgumentException e) {
            return new Outcome(Status.FAILED, null, TagPrompt.VERSION, "invalid_output", e.getMessage(),
                    response.usage(), cost);
        }
    }

    private Optional<JsonNode> headContent(UUID recipeId) {
        return jdbc.sql("""
                        SELECT h.content::text FROM recipe r JOIN recipe_revision h ON h.id = r.head_revision_id
                        WHERE r.id = :id AND r.deleted_at IS NULL AND r.merged_into IS NULL""")
                .param("id", recipeId)
                .query(String.class)
                .optional()
                .map(jsonMapper::readTree);
    }
}
