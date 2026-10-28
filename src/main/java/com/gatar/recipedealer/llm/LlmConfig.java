package com.gatar.recipedealer.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({LlmProperties.class, ApiKeyCheckProperties.class})
public class LlmConfig {

    private static final Logger log = LoggerFactory.getLogger(LlmConfig.class);

    /** Without a key there is no client: every call fails as {@link LlmException.Kind#UNAVAILABLE}. */
    @Bean
    ClaudeClient claudeClient(LlmProperties properties, LlmPricing pricing) {
        if (!properties.enabled()) {
            log.warn("ANTHROPIC_API_KEY is not set: LLM extraction is disabled, captures stop after the heuristic step");
            return new DisabledClaudeClient();
        }
        properties.steps().stream().filter(step -> !pricing.knows(step.model())).forEach(step ->
                log.warn("No price configured for model {} (step {}): its runs are stored without cost", step.model(),
                        step.name()));
        return AnthropicClaudeClient.create(properties, null);
    }

    static final class DisabledClaudeClient implements ClaudeClient {

        @Override
        public Response extract(Request request) throws LlmException {
            throw disabled();
        }

        @Override
        public Response ping(String model) throws LlmException {
            throw disabled();
        }

        private static LlmException disabled() {
            return new LlmException(LlmException.Kind.UNAVAILABLE, "no_api_key", null, "ANTHROPIC_API_KEY is not set", null);
        }
    }
}
