package com.gatar.recipedealer.photo;

import java.io.IOException;
import java.io.InputStream;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.gatar.recipedealer.auth.DevicePrincipal;
import com.gatar.recipedealer.web.ApiException;

/** Upload of a recipe photo from the phone; the recipe references it in {@code images[0]} with the next edit. */
@RestController
public class PhotoController {

    private final PhotoService photos;

    public PhotoController(PhotoService photos) {
        this.photos = photos;
    }

    @PostMapping(path = "/v1/blobs", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public PhotoService.Photo upload(@RequestPart(name = "image", required = false) MultipartFile image,
                                     @AuthenticationPrincipal DevicePrincipal device) throws IOException {
        if (image == null || image.isEmpty()) {
            throw ApiException.badRequest("part 'image' is required");
        }
        try (InputStream in = image.getInputStream()) {
            return photos.upload(in.readAllBytes(), device);
        }
    }
}
