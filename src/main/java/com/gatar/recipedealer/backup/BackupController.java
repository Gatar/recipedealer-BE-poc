package com.gatar.recipedealer.backup;

import java.io.IOException;
import java.util.List;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.gatar.recipedealer.backup.BackupService.BackupInfo;

/** Admin-only (HTTP Basic, see {@code SecurityConfig}). */
@RestController
@RequestMapping("/v1/admin/backups")
public class BackupController {

    private final BackupService backups;

    public BackupController(BackupService backups) {
        this.backups = backups;
    }

    /** {@code label}: optional, part of the file name (e.g. {@code core}). */
    public record CreateRequest(String label) {
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BackupInfo create(@RequestBody(required = false) CreateRequest request) {
        return backups.create(request == null ? null : request.label());
    }

    @GetMapping
    public List<BackupInfo> list() throws IOException {
        return backups.list();
    }

    @GetMapping("/{name}")
    public ResponseEntity<Resource> download(@PathVariable String name) {
        var file = backups.file(name);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
                .body(new FileSystemResource(file));
    }

    @DeleteMapping("/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String name) throws IOException {
        backups.delete(name);
    }
}
