package com.kashmirmenu.app.dto.request;

import com.kashmirmenu.app.enums.RoleName;
import jakarta.validation.constraints.Size;

/** Changes to a staff account. Anything left out (null) stays as it is. */
public record UpdateStaffRequest(
        @Size(max = 100, message = "The name can be up to 100 characters.") String name,
        RoleName role,
        Boolean active) {
}
