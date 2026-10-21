package com.kashmirmenu.app.dto.response;

import com.kashmirmenu.app.enums.CardType;
import com.kashmirmenu.app.enums.PaymentMethod;
import com.kashmirmenu.app.enums.PaymentProvider;
import com.kashmirmenu.app.enums.PaymentStatus;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
public class PaymentResponse {

    private Long id;

    private Long orderId;

    private PaymentMethod paymentMethod;

    private PaymentProvider paymentProvider;

    private CardType cardType;

    private PaymentStatus paymentStatus;

    private String transactionId;

    private BigDecimal amount;

    /** Total refunded so far; zero when nothing was refunded. */
    private BigDecimal refundedAmount;

    private LocalDateTime paidAt;

    private LocalDateTime paymentTimestamp;
}
