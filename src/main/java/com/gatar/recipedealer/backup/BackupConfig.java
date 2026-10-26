package com.gatar.recipedealer.backup;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(BackupProperties.class)
public class BackupConfig {

    @Bean
    DatabaseDumper databaseDumper(BackupProperties properties, JdbcConnectionDetails connection) {
        return new PgDumpDumper(properties.pgDump(), properties.dumpTimeout(), connection);
    }
}
