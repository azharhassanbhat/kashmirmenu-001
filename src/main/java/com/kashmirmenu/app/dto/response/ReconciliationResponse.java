package com.kashmirmenu.app.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** One business day's cash and payment reconciliation. Collection is not settlement: see {@link Settlement}. */
public record ReconciliationResponse(
        LocalDate date,
        String timezone,
        String currency,
        Cash cash,
        List<DigitalLine> digital,
        BigDecimal digitalTotal,
        BigDecimal refundsTotal,
        Pending pending,
        Settlement settlement,
        Closing closing) {

    /**
     * @param expected  cash received that day minus cash refunds: what the drawer should hold
     * @param counts    every count recorded for the day, newest first
     */
    public record Cash(
            BigDecimal collected,
            BigDecimal refunded,
            BigDecimal expected,
            List<Count> counts,
            List<StaffCash> byStaff) {
    }

    public record Count(
            Long id,
            BigDecimal expected,
            BigDecimal counted,
            BigDecimal difference,
            String note,
            String countedBy,
            LocalDateTime recordedAt) {
    }

    /** Cash received per recording staff member, to match against each drawer. No ranking is implied. */
    public record StaffCash(Long staffId, String name, long transactions, BigDecimal amount) {
    }

    public record DigitalLine(String method, String label, long transactions, BigDecimal collected) {
    }

    public record Pending(long count, BigDecimal amount) {
    }

    /**
     * Digital payments are collected when the customer pays but reach the bank later. The app has no
     * bank or gateway feed, so settlement is recorded by a manager; {@code unsettled} is what has been
     * collected but not yet marked settled.
     */
    public record Settlement(
            boolean gatewayConnected,
            long settledCount,
            BigDecimal settledAmount,
            long unsettledCount,
            BigDecimal unsettledAmount) {
    }

    public record Closing(
            BigDecimal grossCollected,
            BigDecimal refunds,
            BigDecimal netCollected,
            BigDecimal cashNet,
            BigDecimal digitalNet,
            /** Vouchers, gift cards, restaurant credit and other methods; cash + digital + other = net. */
            BigDecimal otherNet,
            BigDecimal latestCashDifference) {
    }
}
