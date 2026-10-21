package com.kashmirmenu.app.dto.response;

import java.math.BigDecimal;

/** A choice as it appears on a cart or order line, copied from when it was chosen. */
public record SelectedOptionResponse(String groupName, String optionName, BigDecimal priceDelta) {
}
