package com.kashmirmenu.app.dto.response;

/** A stored photo. {@code url} is what goes in the dish's image field; it is public and unguessable. */
public record ImageUploadResponse(String url, int width, int height, int sizeBytes) {
}
