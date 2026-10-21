package com.gatar.recipedealer.recipe;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class UrlNormalizerTest {

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "https://www.kwestiasmaku.com/przepis/szarlotka                     | https://kwestiasmaku.com/przepis/szarlotka",
            "http://kwestiasmaku.com/przepis/szarlotka/                         | https://kwestiasmaku.com/przepis/szarlotka",
            "HTTPS://WWW.KwestiaSmaku.com/przepis/szarlotka                     | https://kwestiasmaku.com/przepis/szarlotka",
            "https://kwestiasmaku.com:443/przepis/szarlotka                     | https://kwestiasmaku.com/przepis/szarlotka",
            "https://kwestiasmaku.com/przepis/szarlotka?utm_source=fb&utm_medium=social | https://kwestiasmaku.com/przepis/szarlotka",
            "https://kwestiasmaku.com/przepis/szarlotka?fbclid=IwAR0abc         | https://kwestiasmaku.com/przepis/szarlotka",
            "https://example.com/recipe?id=5&gclid=xyz&lang=pl                  | https://example.com/recipe?id=5&lang=pl",
            "https://example.com/recipe?lang=pl&id=5                            | https://example.com/recipe?id=5&lang=pl",
            "https://example.com/recipe#comments                                | https://example.com/recipe",
            "https://example.com/recipe/amp/                                    | https://example.com/recipe",
            "https://example.com/recipe/amp                                     | https://example.com/recipe",
            "https://example.com/recipe?amp=1                                   | https://example.com/recipe",
            "https://example.com/recipe?amp                                     | https://example.com/recipe",
            "https://example.com/recipe?outputType=amp                          | https://example.com/recipe",
            "https://example.com/recipe.amp                                     | https://example.com/recipe",
            "https://amp.example.com/recipe                                     | https://example.com/recipe",
            "https://www.google.com/amp/s/www.example.com/recipe/amp/           | https://example.com/recipe",
            "https://www-example-com.cdn.ampproject.org/c/s/www.example.com/recipe/amp | https://example.com/recipe",
            "https://example.com//recipes//pierogi                              | https://example.com/recipes/pierogi",
            "https://example.com                                                | https://example.com",
            "https://example.com/                                               | https://example.com",
            "https://example.com:8443/recipe                                    | https://example.com:8443/recipe",
            "https://m.example.com/recipe                                       | https://m.example.com/recipe",
            "https://example.com/Przepis/Pierogi                                | https://example.com/Przepis/Pierogi",
            "https://example.com/przepis/%C5%BCurek                             | https://example.com/przepis/%C5%BCurek",
    })
    void normalizes(String raw, String expected) {
        assertThat(UrlNormalizer.normalize(raw)).contains(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "not a url", "ftp://example.com/x", "/relative/path", "mailto:a@b.c", "https://"})
    void rejectsNonHttpUrls(String raw) {
        assertThat(UrlNormalizer.normalize(raw)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "https://www.example.com/recipe/#recipe | https://example.com/recipe#recipe",
            "https://example.com/recipe             | https://example.com/recipe",
    })
    void jsonLdIdsKeepTheFragment(String raw, String expected) {
        assertThat(UrlNormalizer.normalizeKeepingFragment(raw)).contains(expected);
    }
}
