package com.gatar.recipedealer.blob;

import java.io.IOException;
import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BlobStoreConfig {

    @Bean
    BlobStore blobStore(@Value("${recipedealer.storage.blob-dir}") Path blobDir) throws IOException {
        return new FileSystemBlobStore(blobDir);
    }
}
