package com.gatar.recipedealer.backup;

import java.nio.file.Path;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code dir}: where backup archives are kept ({@code BACKUP_DIR}); {@code pgDump}: the pg_dump executable. */
@ConfigurationProperties("recipedealer.backup")
public record BackupProperties(
        Path dir,
        @DefaultValue("pg_dump") String pgDump,
        @DefaultValue("10m") Duration dumpTimeout) {
}
