package com.gatar.recipedealer.llm;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Scripted {@link ClaudeClient}: answers per model in order; an unexpected call fails the test. */
public class StubClaudeClient implements ClaudeClient {

    public final List<Request> requests = new CopyOnWriteArrayList<>();
    public final List<String> pings = new CopyOnWriteArrayList<>();
    private final Map<String, Deque<Object>> scripted = new ConcurrentHashMap<>();

    public void reset() {
        requests.clear();
        pings.clear();
        scripted.clear();
    }

    /** Each outcome is a {@link Response} or an {@link LlmException}; {@code key} is a model or {@code ping:<model>}. */
    public void willAnswer(String key, Object... outcomes) {
        scripted.computeIfAbsent(key, k -> new ArrayDeque<>()).addAll(List.of(outcomes));
    }

    @Override
    public Response extract(Request request) throws LlmException {
        requests.add(request);
        return next(request.model());
    }

    @Override
    public Response ping(String model) throws LlmException {
        pings.add(model);
        return next("ping:" + model);
    }

    private Response next(String key) throws LlmException {
        Deque<Object> outcomes = scripted.get(key);
        Object outcome = outcomes == null ? null : outcomes.poll();
        if (outcome == null) {
            throw new AssertionError("unexpected Claude call for " + key);
        }
        if (outcome instanceof LlmException e) {
            throw e;
        }
        return (Response) outcome;
    }

    public static Response response(String model, String stopReason, String text, long input, long cacheWrite,
                                    long cacheRead, long output) {
        return new Response(text, new LlmUsage(model, stopReason, input, cacheWrite, cacheRead, output, 1234), null);
    }

    /** A complete recipe. */
    public static Response recipe(String model, String title, long input, long cacheWrite, long cacheRead, long output) {
        return response(model, "end_turn", """
                {"recipeFound": true, "title": "%s", "language": "pl", "servings": {"text": null, "count": null},
                 "times": {"prepMinutes": null, "cookMinutes": 45, "totalMinutes": null},
                 "ingredientGroups": [{"name": null, "items": ["1 kg jabłek", "300 g mąki"]}],
                 "stepGroups": [{"name": null, "items": ["Obrać jabłka.", "Piec 45 minut."]}], "notes": []}"""
                .formatted(title), input, cacheWrite, cacheRead, output);
    }

    /** Title and ingredients, no steps. */
    public static Response withoutSteps(String model, String title) {
        return response(model, "end_turn", """
                {"recipeFound": true, "title": "%s", "language": "pl", "servings": {"text": null, "count": null},
                 "times": {"prepMinutes": null, "cookMinutes": null, "totalMinutes": null},
                 "ingredientGroups": [{"name": null, "items": ["1 kg jabłek"]}], "stepGroups": [], "notes": []}"""
                .formatted(title), 100, 0, 0, 50);
    }

    public static Response refusal(String model, String category) {
        return new Response("", new LlmUsage(model, "refusal", 100, 0, 0, 0, 10), category);
    }

    public static LlmException error(LlmException.Kind kind, String type, int status) {
        return new LlmException(kind, type, status, "Claude API error " + status + " (" + type + ")", null);
    }
}
