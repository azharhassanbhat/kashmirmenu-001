package com.kashmirmenu.app.dto.request;

import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** Landing-page feedback. No sign-in; {@code contact} is read only when {@code wantsReply} is true. */
public record AnonymousFeedbackRequest(
        BigDecimal rating,
        @Size(max = 20) String category,
        @Size(max = 2000) String comment,
        Boolean wantsReply,
        @Size(max = 300) String contact,
        /* Optional: the public token of an order the guest placed here, to say which visit this is about. */
        @Size(max = 100) String orderToken) {
}
