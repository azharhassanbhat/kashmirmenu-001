package com.kashmirmenu.app.dto.request;

import com.kashmirmenu.app.service.MenuCsv;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The text of a CSV file. The server reads it; nothing the browser worked out is trusted. */
public record MenuImportRequest(
        @NotBlank(message = "The file is empty.")
        @Size(max = MenuCsv.MAX_CHARS, message = "The file is too large.") String csv) {
}
