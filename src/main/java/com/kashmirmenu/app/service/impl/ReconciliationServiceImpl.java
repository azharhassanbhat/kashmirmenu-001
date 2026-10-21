package com.kashmirmenu.app.service.impl;

import com.kashmirmenu.app.analytics.DateRange;
import com.kashmirmenu.app.analytics.HourlyFact;
import com.kashmirmenu.app.analytics.MoneyMath;
import com.kashmirmenu.app.analytics.OpenFact;
import com.kashmirmenu.app.analytics.PaymentFilter;
import com.kashmirmenu.app.config.BusinessTime;
import com.kashmirmenu.app.dto.request.RecordCashCountRequest;
import com.kashmirmenu.app.dto.response.ReconciliationResponse;
import com.kashmirmenu.app.entity.CashReconciliation;
import com.kashmirmenu.app.entity.Restaurant;
import com.kashmirmenu.app.enums.PaymentMethod;
import com.kashmirmenu.app.enums.PaymentStatus;
import com.kashmirmenu.app.exception.AccessDeniedException;
import com.kashmirmenu.app.repository.CashReconciliationRepository;
import com.kashmirmenu.app.repository.PaymentAnalyticsQueries;
import com.kashmirmenu.app.security.service.CurrentUserService;
import com.kashmirmenu.app.service.ReconciliationService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Daily reconciliation. Everything is computed from the same payment records as the dashboard, for one
 * business day in the restaurant's time zone. "Collected" is when a customer paid; "settled" is when a
 * manager confirmed the money reached the bank, which the app cannot observe on its own.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class ReconciliationServiceImpl implements ReconciliationService {

    private final PaymentAnalyticsQueries queries;
    private final CashReconciliationRepository cashRepository;
    private final CurrentUserService currentUserService;
    private final BusinessTime businessTime;

    @Value("${app.currency:INR}")
    private String currency;

    @Override
    @Transactional(readOnly = true)
    public ReconciliationResponse forDate(LocalDate date) {
        return build(restaurant(), date);
    }

    @Override
    public ReconciliationResponse recordCount(RecordCashCountRequest request) {
        Restaurant restaurant = restaurant();
        LocalDate date = request.date();

        if (date.isAfter(businessTime.today())) {
            throw new IllegalArgumentException("A cash count cannot be recorded for a future date.");
        }

        BigDecimal expected = expectedCash(restaurant.getId(), dayOf(date));
        BigDecimal counted = MoneyMath.money(request.countedCash());

        CashReconciliation record = new CashReconciliation();
        record.setRestaurant(restaurant);
        record.setBusinessDate(date);
        record.setExpectedCash(expected);
        record.setCountedCash(counted);
        record.setDifference(counted.subtract(expected));
        record.setNote(request.note() == null || request.note().isBlank() ? null : request.note().trim());
        record.setCountedBy(currentUserService.getCurrentUser());
        cashRepository.save(record);

        return build(restaurant, date);
    }

    @Override
    public ReconciliationResponse markDigitalSettled(LocalDate date) {
        Restaurant restaurant = restaurant();
        if (date.isAfter(businessTime.today())) {
            throw new IllegalArgumentException("Payments cannot be settled for a future date.");
        }
        queries.markDigitalSettled(restaurant.getId(), dayOf(date), businessTime.now());
        return build(restaurant, date);
    }

    // ------------------------------------------------------------------

    private ReconciliationResponse build(Restaurant restaurant, LocalDate date) {
        Long rid = restaurant.getId();
        DateRange day = dayOf(date);
        PaymentFilter everything = PaymentFilter.of(rid, null, null, null, null);

        List<HourlyFact> collected = queries.collected(everything, day);
        List<HourlyFact> refunds = queries.refunds(everything, day);

        Map<PaymentMethod, BigDecimal> grossByMethod = new EnumMap<>(PaymentMethod.class);
        Map<PaymentMethod, Long> countByMethod = new EnumMap<>(PaymentMethod.class);
        for (HourlyFact f : collected) {
            grossByMethod.merge(f.method(), nz(f.amount()), BigDecimal::add);
            countByMethod.merge(f.method(), nz(f.count()), Long::sum);
        }
        Map<PaymentMethod, BigDecimal> refundsByMethod = new EnumMap<>(PaymentMethod.class);
        for (HourlyFact f : refunds) {
            refundsByMethod.merge(f.method(), nz(f.amount()), BigDecimal::add);
        }

        BigDecimal cashCollected = grossByMethod.getOrDefault(PaymentMethod.CASH, BigDecimal.ZERO);
        BigDecimal cashRefunded = refundsByMethod.getOrDefault(PaymentMethod.CASH, BigDecimal.ZERO);
        BigDecimal expected = cashCollected.subtract(cashRefunded);

        List<ReconciliationResponse.Count> counts = new ArrayList<>();
        for (CashReconciliation c : cashRepository.findForDate(rid, date)) {
            counts.add(new ReconciliationResponse.Count(
                    c.getId(), c.getExpectedCash(), c.getCountedCash(), c.getDifference(), c.getNote(),
                    c.getCountedBy() == null ? null : c.getCountedBy().getName(),
                    c.getCreatedAt()));
        }

        List<ReconciliationResponse.StaffCash> byStaff = new ArrayList<>();
        for (Object[] row : queries.cashByStaff(rid, day)) {
            Long staffId = (Long) row[0];
            byStaff.add(new ReconciliationResponse.StaffCash(
                    staffId,
                    row[1] == null ? "Not attributed" : (String) row[1],
                    ((Number) row[2]).longValue(),
                    MoneyMath.money((BigDecimal) row[3])));
        }

        List<ReconciliationResponse.DigitalLine> digital = new ArrayList<>();
        BigDecimal digitalGross = BigDecimal.ZERO;
        BigDecimal digitalRefunds = BigDecimal.ZERO;
        BigDecimal otherGross = BigDecimal.ZERO;
        BigDecimal otherRefunds = BigDecimal.ZERO;
        for (PaymentMethod m : PaymentMethod.values()) {
            BigDecimal gross = grossByMethod.getOrDefault(m, BigDecimal.ZERO);
            BigDecimal refunded = refundsByMethod.getOrDefault(m, BigDecimal.ZERO);
            if (m.isDigital()) {
                digitalGross = digitalGross.add(gross);
                digitalRefunds = digitalRefunds.add(refunded);
                if (countByMethod.containsKey(m)) {
                    digital.add(new ReconciliationResponse.DigitalLine(
                            m.name(), m.label(), countByMethod.get(m), MoneyMath.money(gross)));
                }
            } else if (!m.isCash()) {
                otherGross = otherGross.add(gross);
                otherRefunds = otherRefunds.add(refunded);
            }
        }

        BigDecimal grossTotal = cashCollected.add(digitalGross).add(otherGross);
        BigDecimal refundsTotal = cashRefunded.add(digitalRefunds).add(otherRefunds);

        long pendingCount = 0;
        BigDecimal pendingAmount = BigDecimal.ZERO;
        for (OpenFact f : queries.open(everything, day)) {
            if (f.status() == PaymentStatus.PENDING) {
                pendingCount += nz(f.count());
                pendingAmount = pendingAmount.add(nz(f.amount()));
            }
        }

        Object[] settled = queries.digitalSettlement(rid, day, true);
        Object[] unsettled = queries.digitalSettlement(rid, day, false);

        BigDecimal latestDifference = counts.isEmpty() ? null : counts.get(0).difference();

        return new ReconciliationResponse(
                date,
                businessTime.zone().getId(),
                currency,
                new ReconciliationResponse.Cash(
                        MoneyMath.money(cashCollected), MoneyMath.money(cashRefunded), MoneyMath.money(expected),
                        counts, byStaff),
                digital,
                MoneyMath.money(digitalGross),
                MoneyMath.money(refundsTotal),
                new ReconciliationResponse.Pending(pendingCount, MoneyMath.money(pendingAmount)),
                new ReconciliationResponse.Settlement(
                        false,
                        count(settled), MoneyMath.money(sum(settled)),
                        count(unsettled), MoneyMath.money(sum(unsettled))),
                new ReconciliationResponse.Closing(
                        MoneyMath.money(grossTotal),
                        MoneyMath.money(refundsTotal),
                        MoneyMath.money(grossTotal.subtract(refundsTotal)),
                        MoneyMath.money(expected),
                        MoneyMath.money(digitalGross.subtract(digitalRefunds)),
                        MoneyMath.money(otherGross.subtract(otherRefunds)),
                        latestDifference));
    }

    /** Cash received on the day minus cash refunded on the day: what the drawer should hold. */
    private BigDecimal expectedCash(Long restaurantId, DateRange day) {
        PaymentFilter cash = PaymentFilter.of(restaurantId, List.of(PaymentMethod.CASH), null, null, null);
        BigDecimal in = BigDecimal.ZERO;
        for (HourlyFact f : queries.collected(cash, day)) {
            in = in.add(nz(f.amount()));
        }
        BigDecimal out = BigDecimal.ZERO;
        for (HourlyFact f : queries.refunds(cash, day)) {
            out = out.add(nz(f.amount()));
        }
        return MoneyMath.money(in.subtract(out));
    }

    private static DateRange dayOf(LocalDate date) {
        return new DateRange(date.atStartOfDay(), date.plusDays(1).atStartOfDay());
    }

    private Restaurant restaurant() {
        Restaurant restaurant = currentUserService.getCurrentRestaurant();
        if (restaurant == null) {
            throw new AccessDeniedException("This account is not attached to a restaurant.");
        }
        return restaurant;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static long nz(Long v) {
        return v == null ? 0 : v;
    }

    private static long count(Object[] row) {
        return row[0] == null ? 0 : ((Number) row[0]).longValue();
    }

    private static BigDecimal sum(Object[] row) {
        return row[1] == null ? BigDecimal.ZERO : (BigDecimal) row[1];
    }
}
