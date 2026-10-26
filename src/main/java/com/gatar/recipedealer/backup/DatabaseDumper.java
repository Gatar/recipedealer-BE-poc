package com.gatar.recipedealer.backup;

import java.io.IOException;
import java.nio.file.Path;

/** Writes a full dump of the application database in {@code pg_dump --format=custom} to {@code target}. */
@FunctionalInterface
public interface DatabaseDumper {

    void dump(Path target) throws IOException;
}
