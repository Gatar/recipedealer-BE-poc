package com.gatar.recipedealer.translation;

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

import com.gatar.recipedealer.common.Jdbc;
import com.gatar.recipedealer.llm.ClaudeClient;
import com.gatar.recipedealer.llm.LlmException;
import com.gatar.recipedealer.llm.LlmPricing;
import com.gatar.recipedealer.llm.TranslatePrompt;
import com.gatar.recipedealer.recipe.RecipeRepository;
import com.gatar.recipedealer.recipe.RecipeRepository.Origin;
import com.gatar.recipedealer.recipe.ResolutionService;
import com.gatar.recipedealer.recipe.RevisionService;
import com.gatar.recipedealer.tag.AutoTagRepository;
import com.gatar.recipedealer.tag.TagService;
import com.gatar.recipedealer.translation.AutoTranslationRepository.Outcome;
import com.gatar.recipedealer.translation.AutoTranslationRepository.Pending;
import com.gatar.recipedealer.translation.AutoTranslationRepository.Status;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The translation job of a capture: one Claude call per new recipe to translate (outside any transaction), then the
 * translation becomes a machine revision (head; the original stays in the history) and the recipe's tagging is
 * requested, so tags are chosen from the translated text. A failed call never blocks the capture: the recipe stays in
 * its language and is still tagged.
 */
@Service
public class AutoTranslator {

    /** Job step of the translation job ({@code process_capture} jobs, so they count as processing in progress). */
    public static final String STEP = "translation";

    private static final Logger log = LoggerFactory.getLogger(AutoTranslator.class);

    private final AutoTranslationRepository translations;
    private final AutoTagRepository autoTags;
    private final TagService tags;
    private final RevisionService revisions;
    private final RecipeRepository recipes;
    private final ResolutionService resolutions;
    private final ClaudeClient client;
    private final LlmPricing pricing;
    private final AutoTranslationProperties properties;
    private final JdbcClient jdbc;
    private final JsonMapper jsonMapper;
    private final TransactionTemplate tx;

    public AutoTranslator(AutoTranslationRepository translations, AutoTagRepository autoTags, TagService tags,
                          RevisionService revisions, RecipeRepository recipes, ResolutionService resolutions,
                          ClaudeClient client, LlmPricing pricing, AutoTranslationProperties properties,
                          JdbcClient jdbc, JsonMapper jsonMapper, TransactionTemplate tx) {
        this.translations = translations;
        this.autoTags = autoTags;
        this.tags = tags;
        this.revisions = revisions;
        this.recipes = recipes;
        this.resolutions = resolutions;
        this.client = client;
        this.pricing = pricing;
        this.properties = properties;
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
        this.tx = tx;
    }

    /** The head a translation starts from. */
    private record Head(UUID revisionId, JsonNode content, UUID runId, int schemaVersion, Origin origin,
                        boolean needsReview) {
    }

    /** A call's result; {@code translated} is set only for a translation to apply. */
    private record Result(Outcome outcome, Head head, JsonNode translated) {
    }

    public void run(UUID captureId) {
        List<Pending> pending = translations.pending(captureId);
        List<Result> results = new ArrayList<>();
        for (Pending translation : pending) {
            results.add(translate(translation));
        }
        tx.executeWithoutResult(status -> {
            for (int i = 0; i < pending.size(); i++) {
                UUID recipeId = pending.get(i).recipeId();
                Outcome outcome = results.get(i).translated() == null ? results.get(i).outcome()
                        : apply(recipeId, captureId, results.get(i));
                translations.finish(pending.get(i).id(), outcome);
                log.info("Translated recipe {} of capture {} to {}: {}{}", recipeId, captureId,
                        pending.get(i).targetLanguage(), outcome.status().name().toLowerCase(Locale.ROOT),
                        outcome.errorType() == null ? "" : " (" + outcome.errorType() + ")");
                if (tags.tagsOf(recipeId).isEmpty()) {
                    autoTags.request(recipeId, captureId);
                }
            }
            resolutions.refreshState(captureId);
        });
    }

    private Result translate(Pending translation) {
        Optional<Head> head = head(translation.recipeId());
        if (head.isEmpty()) {
            return skipped("recipe_gone", null);
        }
        if (head.get().origin() == Origin.USER) {
            return skipped("edited_by_user", head.get());
        }
        var request = new ClaudeClient.Request(properties.model(), properties.effort(), TranslatePrompt.SYSTEM_PROMPT,
                TranslatePrompt.schema(), List.of(new ClaudeClient.Text(
                        TranslatePrompt.input(translation.targetLanguage(), head.get().content()))));
        ClaudeClient.Response response;
        try {
            response = client.extract(request);
        } catch (LlmException e) {
            return failed(e.errorType(), e.getMessage(), null, head.get());
        } catch (RuntimeException e) {
            log.warn("Translating recipe {} failed", translation.recipeId(), e);
            return failed("error", String.valueOf(e), null, head.get());
        }
        String problem = response.refusalCategory() != null ? "refusal:" + response.refusalCategory()
                : "max_tokens".equals(response.usage().stopReason()) ? "max_tokens" : null;
        if (problem != null) {
            return failed(problem, null, response, head.get());
        }
        TranslatePrompt.Parsed parsed;
        try {
            parsed = TranslatePrompt.parse(response.text(), head.get().content(), translation.targetLanguage());
        } catch (IllegalArgumentException e) {
            return failed("invalid_output", e.getMessage(), response, head.get());
        }
        var cost = pricing.cost(response.usage());
        if (parsed.alreadyInTarget()) {
            return new Result(new Outcome(Status.SKIPPED, parsed.sourceLanguage(), null, TranslatePrompt.VERSION,
                    "already_in_target", null, response.usage(), cost), head.get(), null);
        }
        return new Result(new Outcome(Status.SUCCEEDED, parsed.sourceLanguage(), null, TranslatePrompt.VERSION, null,
                null, response.usage(), cost), head.get(), parsed.recipe());
    }

    /** Stores the translation as the new head, unless the recipe changed since it was read. */
    private Outcome apply(UUID recipeId, UUID captureId, Result result) {
        Outcome outcome = result.outcome();
        var current = recipes.lockHead(recipeId);
        if (current.isEmpty() || current.get().deleted() || current.get().mergedInto() != null
                || !result.head().revisionId().equals(current.get().headRevisionId())) {
            return new Outcome(Status.SKIPPED, outcome.sourceLanguage(), null, outcome.promptVersion(),
                    "recipe_changed", null, outcome.usage(), outcome.cost());
        }
        Head head = result.head();
        var saved = revisions.addRevision(recipeId, new RevisionService.Content(result.translated(),
                head.schemaVersion(), Origin.MACHINE, head.runId(), captureId, null, null, head.needsReview()), false);
        return new Outcome(Status.SUCCEEDED, outcome.sourceLanguage(), saved.created() ? saved.revisionId() : null,
                outcome.promptVersion(), null, null, outcome.usage(), outcome.cost());
    }

    private static Result skipped(String reason, Head head) {
        return new Result(new Outcome(Status.SKIPPED, null, null, null, reason, null, null, null), head, null);
    }

    private Result failed(String errorType, String error, ClaudeClient.Response response, Head head) {
        var usage = response == null ? null : response.usage();
        return new Result(new Outcome(Status.FAILED, null, null, TranslatePrompt.VERSION, errorType, error, usage,
                pricing.cost(usage)), head, null);
    }

    private Optional<Head> head(UUID recipeId) {
        return jdbc.sql("""
                        SELECT h.id, h.content::text AS content, h.run_id, h.schema_version, h.origin, h.needs_review
                        FROM recipe r JOIN recipe_revision h ON h.id = r.head_revision_id
                        WHERE r.id = :id AND r.deleted_at IS NULL AND r.merged_into IS NULL""")
                .param("id", recipeId)
                .query((rs, n) -> new Head(Jdbc.uuid(rs, "id"), jsonMapper.readTree(rs.getString("content")),
                        Jdbc.uuid(rs, "run_id"), rs.getInt("schema_version"), Origin.valueOf(rs.getString("origin")),
                        rs.getBoolean("needs_review")))
                .optional();
    }
}
