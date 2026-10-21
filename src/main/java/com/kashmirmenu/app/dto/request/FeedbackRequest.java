package com.kashmirmenu.app.dto.request;

/**
 * A guest's rating and optional comment. The rating is read as a decimal so that 2.5 is REFUSED, not rounded down. Deliberately not annotated with Bean Validation: FeedbackRules
 * answers with a message written for a guest rather than a generic constraint message.
 */
public record FeedbackRequest(java.math.BigDecimal rating, String comment) {
}
