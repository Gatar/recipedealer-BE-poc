package com.gatar.recipedealer.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.gatar.recipedealer.IntegrationTest;

/** {@code docs/api.md} documents exactly the endpoints the application maps, one {@code ### METHOD /path} heading each. */
@IntegrationTest
class ApiDocumentationTest {

    private static final Path API_DOC = Path.of("docs/api.md");
    private static final Pattern HEADING = Pattern.compile("^### (GET|POST|PUT|PATCH|DELETE) (/\\S+)$", Pattern.MULTILINE);

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    @Test
    void everyEndpointIsDocumentedAndEveryDocumentedEndpointExists() throws IOException {
        Set<String> mapped = new TreeSet<>();
        mappings.getHandlerMethods().keySet().forEach(info -> info.getPatternValues().stream()
                .filter(path -> path.startsWith("/v1/"))
                .forEach(path -> info.getMethodsCondition().getMethods()
                        .forEach(method -> mapped.add(method + " " + path))));

        Set<String> documented = new TreeSet<>();
        Matcher heading = HEADING.matcher(Files.readString(API_DOC));
        while (heading.find()) {
            assertThat(documented.add(heading.group(1) + " " + heading.group(2)))
                    .as("documented twice: %s", heading.group())
                    .isTrue();
        }

        assertThat(mapped).as("endpoints in the code (left) vs. %s (right)", API_DOC).isEqualTo(documented);
    }
}
