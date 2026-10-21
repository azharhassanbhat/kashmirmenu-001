package com.kashmirmenu.app.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MarkNotificationReadRequest {

    @NotNull
    private Boolean read;
}