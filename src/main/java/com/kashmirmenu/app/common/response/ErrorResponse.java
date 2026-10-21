package com.kashmirmenu.app.common.response;

import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

@Setter
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ErrorResponse {

    private boolean success;

    private String message;
    @Builder.Default
    private List<FieldError> errors = List.of();

    @Builder.Default
    private LocalDateTime timestamp = LocalDateTime.now();
}