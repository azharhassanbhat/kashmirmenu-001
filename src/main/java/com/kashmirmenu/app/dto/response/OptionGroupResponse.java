package com.kashmirmenu.app.dto.response;

import com.kashmirmenu.app.enums.OptionKind;

import java.util.List;

/** A group of choices on a dish: "Portion", "Extras", "Spice level". */
public record OptionGroupResponse(
        Long id,
        String name,
        OptionKind kind,
        boolean required,
        Integer maxSelect,
        List<OptionResponse> options) {
}
