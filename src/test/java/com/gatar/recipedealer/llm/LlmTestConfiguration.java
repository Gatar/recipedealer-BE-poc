package com.gatar.recipedealer.llm;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** With {@code recipedealer.llm.api-key} set: LLM steps enabled, every call answered by the stub (never the real API). */
@TestConfiguration(proxyBeanMethods = false)
public class LlmTestConfiguration {

    @Bean
    @Primary
    StubClaudeClient stubClaudeClient() {
        return new StubClaudeClient();
    }
}
