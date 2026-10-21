package com.gatar.recipedealer.capture;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gatar.recipedealer.auth.DevicePrincipal;
import com.gatar.recipedealer.web.NotFoundException;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/v1/captures")
public class CaptureController {

    private final CaptureIngestService ingest;
    private final CaptureRepository captures;

    public CaptureController(CaptureIngestService ingest, CaptureRepository captures) {
        this.ingest = ingest;
        this.captures = captures;
    }

    /** Multipart: part {@code capture} (JSON) plus one part per attachment, named like {@code attachments[].name}. */
    @PutMapping(path = "/{captureId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<CaptureStatusView> put(@PathVariable UUID captureId, HttpServletRequest request,
                                                 @AuthenticationPrincipal DevicePrincipal device)
            throws IOException, ServletException {
        boolean created = ingest.ingest(captureId, request.getParts(), device);
        return ResponseEntity.status(created ? HttpStatus.CREATED : HttpStatus.OK).body(status(captureId));
    }

    @GetMapping("/{captureId}")
    public CaptureStatusView get(@PathVariable UUID captureId) {
        return status(captureId);
    }

    private CaptureStatusView status(UUID captureId) {
        var state = captures.findState(captureId)
                .orElseThrow(() -> new NotFoundException("capture " + captureId + " not found"));
        return new CaptureStatusView(captureId, state.status().value(), List.of(), state.message());
    }
}
