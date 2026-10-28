package com.gatar.recipedealer.admin;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.gatar.recipedealer.llm.ApiKeyCheck;
import com.gatar.recipedealer.processing.ExtractionStatsService;
import com.gatar.recipedealer.processing.LlmExtractionService;

/** LLM extraction administration: model comparisons, reprocessing, the API key check. */
@RestController
@RequestMapping("/v1/admin")
public class LlmAdminController {

    private final LlmExtractionService llm;
    private final ExtractionStatsService stats;
    private final ApiKeyCheck apiKeyCheck;

    public LlmAdminController(LlmExtractionService llm, ExtractionStatsService stats, ApiKeyCheck apiKeyCheck) {
        this.llm = llm;
        this.stats = stats;
        this.apiKeyCheck = apiKeyCheck;
    }

    /** {@code steps}: LLM step names, e.g. {@code ["haiku", "sonnet"]}. */
    public record ComparisonRequest(List<UUID> captureIds, List<String> steps) {
    }

    public record ComparisonQueued(List<LlmExtractionService.QueuedComparison> queued) {
    }

    @PostMapping("/llm-comparisons")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ComparisonQueued compare(@RequestBody ComparisonRequest request) {
        return new ComparisonQueued(llm.compare(request.captureIds(), request.steps()));
    }

    @GetMapping("/llm-comparisons")
    public List<ExtractionStatsService.CaptureComparison> comparison(@RequestParam List<UUID> captureIds) {
        return stats.comparison(captureIds);
    }

    /** Either {@code captureIds} or {@code reason} (e.g. {@code AWAITING_EXTRACTION}). */
    public record ReprocessRequest(List<UUID> captureIds, String reason) {
    }

    @PostMapping("/llm-extractions")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public LlmExtractionService.Reprocessing reprocess(@RequestBody ReprocessRequest request) {
        return llm.reprocess(request.captureIds(), request.reason());
    }

    /** Runs the API key check now (pings the dead man's switch URL on success, like the scheduled check). */
    @PostMapping("/api-key-check")
    public ApiKeyCheck.Result checkApiKey() {
        return apiKeyCheck.check();
    }
}
