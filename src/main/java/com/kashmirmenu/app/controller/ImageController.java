package com.kashmirmenu.app.controller;

import com.kashmirmenu.app.dto.response.ImageUploadResponse;
import com.kashmirmenu.app.exception.BadRequestException;
import com.kashmirmenu.app.exception.ResourceNotFoundException;
import com.kashmirmenu.app.service.ImageService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;

/**
 * Photos: staff upload them, everyone (including guests who are not signed in) can see them. A photo's
 * address is 128 random bits, so it is public in the sense that holding it is enough, and unguessable.
 */
@RestController
@RequiredArgsConstructor
public class ImageController {

    private final ImageService imageService;

    @Operation(summary = "Upload a dish photo (JPEG or PNG, up to 10 MB). It is re-encoded; the returned url goes in the dish's image field")
    // No "consumes" restriction on purpose: a request that is not a multipart form then reaches Spring's
    // multipart check and is answered with a clear 400, instead of an unsupported-media-type error this
    // application would otherwise report as a 500.
    @PostMapping("/api/v1/images")
    @PreAuthorize("hasAnyRole('ADMIN','RESTAURANT_OWNER','MANAGER')")
    @ResponseStatus(HttpStatus.CREATED)
    public ImageUploadResponse upload(@RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw new BadRequestException("The file is empty.");
        }
        // The file's name and the Content-Type the browser claims are ignored: only its bytes are trusted.
        return imageService.store(file.getBytes());
    }

    @Operation(summary = "A stored photo. Public; the key in the address is unguessable")
    @GetMapping("/api/public/images/{key}")
    public ResponseEntity<byte[]> get(@PathVariable String key) {
        ImageService.Content content = imageService.find(key)
                .orElseThrow(() -> new ResourceNotFoundException("Photo not found."));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.contentType()))
                // The address never changes what it shows (a new upload gets a new key), so browsers and
                // any cache may keep it for a year without asking again.
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                .body(content.bytes());
    }
}
