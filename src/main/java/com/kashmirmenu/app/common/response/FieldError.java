package com.kashmirmenu.app.common.response;

import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FieldError {

    private String field;

    private String message;
}