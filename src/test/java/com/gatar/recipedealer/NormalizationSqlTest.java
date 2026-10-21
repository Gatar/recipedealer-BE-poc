package com.gatar.recipedealer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

@IntegrationTest
class NormalizationSqlTest {

    @Autowired
    JdbcClient jdbc;

    private String normalize(String input) {
        return jdbc.sql("SELECT rd_normalize(:input)").param("input", input).query(String.class).single();
    }

    @Test
    void removesPolishDiacriticsIncludingLStroke() {
        assertThat(normalize("Łódź")).isEqualTo("lodz");
        assertThat(normalize("ZAŻÓŁĆ GĘŚLĄ JAŹŃ")).isEqualTo("zazolc gesla jazn");
    }

    @Test
    void lowerCasesAndCollapsesWhitespace() {
        assertThat(normalize("  Sernik \t z   Rosemarie\n ")).isEqualTo("sernik z rosemarie");
    }

    @Test
    void wrappersAreImmutable() {
        var volatility = jdbc.sql("SELECT DISTINCT provolatile FROM pg_proc WHERE proname IN ('rd_unaccent', 'rd_normalize')")
                .query(String.class).list();
        assertThat(volatility).containsExactly("i");
    }

    @Test
    void databaseUsesUtf8() {
        var encoding = jdbc.sql("SELECT pg_encoding_to_char(encoding) FROM pg_database WHERE datname = current_database()")
                .query(String.class).single();
        assertThat(encoding).isEqualTo("UTF8");
    }
}
