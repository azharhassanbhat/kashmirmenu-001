package com.kashmirmenu.app.dto.request;

import com.kashmirmenu.app.enums.OptionKind;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * The complete set of option groups for one dish. Sent whole and reconciled by id, so the editor
 * can add, rename, reorder and remove in one save. A group or option with no id is new; one whose
 * id is missing from the request is removed.
 */
public record MenuOptionsRequest(
        @NotNull(message = "Groups are required.")
        @Size(max = 10, message = "A dish can have at most 10 option groups.")
        @Valid List<Group> groups) {

    public record Group(
            Long id,
            @NotBlank(message = "Every group needs a name.")
            @Size(max = 80, message = "Group names can be up to 80 characters.") String name,
            @NotNull(message = "Choose single or multiple choice.") OptionKind kind,
            Boolean required,
            @Min(value = 1, message = "The maximum must be at least 1.")
            @Max(value = 20, message = "The maximum can be at most 20.") Integer maxSelect,
            @NotEmpty(message = "Every group needs at least one option.")
            @Size(max = 30, message = "A group can have at most 30 options.")
            @Valid List<Option> options) {
    }

    public record Option(
            Long id,
            @NotBlank(message = "Every option needs a name.")
            @Size(max = 80, message = "Option names can be up to 80 characters.") String name,
            @NotNull(message = "Every option needs a price change (0 for none).")
            @DecimalMin(value = "-9999.99", message = "Price change is too low.")
            @DecimalMax(value = "9999.99", message = "Price change is too high.") BigDecimal priceDelta,
            Boolean available,
            Boolean defaultOption) {
    }
}
