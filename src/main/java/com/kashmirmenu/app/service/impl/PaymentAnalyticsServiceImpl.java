package com.kashmirmenu.app.service.impl;

import com.kashmirmenu.app.analytics.AnalyticsCalculator;
import com.kashmirmenu.app.analytics.AnalyticsInput;
import com.kashmirmenu.app.analytics.AnalyticsOverview;
import com.kashmirmenu.app.analytics.AnalyticsQuery;
import com.kashmirmenu.app.analytics.Granularity;
import com.kashmirmenu.app.analytics.PaymentFilter;
import com.kashmirmenu.app.analytics.PeriodResolver;
import com.kashmirmenu.app.analytics.ResolvedPeriod;
import com.kashmirmenu.app.common.response.PageResponse;
import com.kashmirmenu.app.config.BusinessTime;
import com.kashmirmenu.app.dto.response.PaymentMetaResponse;
import com.kashmirmenu.app.dto.response.PaymentTransactionDetailResponse;
import com.kashmirmenu.app.dto.response.PaymentTransactionResponse;
import com.kashmirmenu.app.entity.Order;
import com.kashmirmenu.app.entity.Payment;
import com.kashmirmenu.app.entity.PaymentRefund;
import com.kashmirmenu.app.entity.Restaurant;
import com.kashmirmenu.app.enums.CardType;
import com.kashmirmenu.app.enums.PaymentMethod;
import com.kashmirmenu.app.enums.PaymentProvider;
import com.kashmirmenu.app.enums.PaymentStatus;
import com.kashmirmenu.app.exception.AccessDeniedException;
import com.kashmirmenu.app.repository.PaymentAnalyticsQueries;
import com.kashmirmenu.app.repository.PaymentRefundRepository;
import com.kashmirmenu.app.repository.PaymentRepository;
import com.kashmirmenu.app.repository.UserRepository;
import com.kashmirmenu.app.security.service.CurrentUserService;
import com.kashmirmenu.app.service.PaymentAnalyticsService;
import com.kashmirmenu.app.specification.PaymentSpecifications;
import com.kashmirmenu.app.util.PageResponseUtils;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PaymentAnalyticsServiceImpl implements PaymentAnalyticsService {

    /** The only columns a client may sort by. Anything else is rejected rather than passed to the query. */
    private static final Map<String, String> SORT_FIELDS = Map.of(
            "time", "paymentTimestamp",
            "amount", "amount",
            "method", "paymentMethod",
            "status", "paymentStatus",
            "id", "id");

    private static final int MAX_PAGE_SIZE = 100;
    private static final int EXPORT_PAGE_SIZE = 500;
    /** A hard ceiling so one export cannot read the whole table into a response. */
    static final int MAX_EXPORT_ROWS = 50_000;

    private static final DateTimeFormatter CSV_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final PaymentAnalyticsQueries queries;
    private final PaymentRepository paymentRepository;
    private final PaymentRefundRepository refundRepository;
    private final UserRepository userRepository;
    private final CurrentUserService currentUserService;
    private final BusinessTime businessTime;

    @Value("${app.currency:INR}")
    private String currency;

    // ------------------------------------------------------------------ meta

    @Override
    public PaymentMetaResponse meta() {
        Long restaurantId = restaurantId();

        List<PaymentMetaResponse.Method> methods = Arrays.stream(PaymentMethod.values())
                .map(m -> new PaymentMetaResponse.Method(
                        m.name(), m.label(), m.isDigital(), m.isCash(), m.isDinerSelectable(),
                        providerOptions(m)))
                .toList();

        List<PaymentMetaResponse.Status> statuses = Arrays.stream(PaymentStatus.values())
                .map(s -> new PaymentMetaResponse.Status(s.name(), s.label(), s.isCaptured()))
                .toList();

        List<PaymentMetaResponse.Option> cardTypes = Arrays.stream(CardType.values())
                .map(c -> new PaymentMetaResponse.Option(c.name(), c.label()))
                .toList();

        List<PaymentMetaResponse.Staff> staff = userRepository.findByRestaurantIdOrderByNameAsc(restaurantId)
                .stream()
                .map(u -> new PaymentMetaResponse.Staff(u.getId(), u.getName()))
                .toList();

        return new PaymentMetaResponse(currency, businessTime.zone().getId(), methods, statuses, cardTypes, staff);
    }

    private static List<PaymentMetaResponse.Option> providerOptions(PaymentMethod m) {
        if (!m.hasProviders()) {
            return List.of();
        }
        List<PaymentMetaResponse.Option> options = new java.util.ArrayList<>();
        for (PaymentProvider p : PaymentProvider.forMethod(m)) {
            options.add(new PaymentMetaResponse.Option(p.name(), p.label()));
        }
        options.add(new PaymentMetaResponse.Option(
                m == PaymentMethod.UPI ? AnalyticsCalculator.UNKNOWN_UPI : AnalyticsCalculator.UNKNOWN_WALLET,
                m == PaymentMethod.UPI ? "Unknown UPI app" : "Unknown wallet"));
        return options;
    }

    // ------------------------------------------------------------------ overview

    @Override
    public AnalyticsOverview overview(AnalyticsQuery q) {
        Long restaurantId = restaurantId();

        ResolvedPeriod period = resolve(q);
        Granularity granularity = q.granularity() != null ? q.granularity() : Granularity.auto(period.current());
        granularity.buckets(period.current()); // reject an unreasonable axis before any query runs

        PaymentFilter filter = filterOf(restaurantId, q);

        AnalyticsInput input = new AnalyticsInput(
                currency,
                businessTime.zone().getId(),
                period,
                granularity,
                queries.collected(filter, period.current()),
                queries.refunds(filter, period.current()),
                queries.collected(filter, period.previous()),
                queries.refunds(filter, period.previous()),
                queries.open(filter, period.current()),
                queries.uniqueOrdersPaid(filter, period.current()),
                queries.uniqueOrdersPaid(filter, period.previous()),
                // Orders cannot be narrowed by payment method, so sales are only meaningful unfiltered.
                filter.narrowsPayments() ? null : queries.sales(restaurantId, period.current()));

        return AnalyticsCalculator.compute(input);
    }

    // ------------------------------------------------------------------ transactions

    @Override
    public PageResponse<PaymentTransactionResponse> transactions(
            AnalyticsQuery q, String search, int page, int size, String sortBy, String direction) {

        Long restaurantId = restaurantId();
        ResolvedPeriod period = resolve(q);
        PaymentFilter filter = filterOf(restaurantId, q);

        Pageable pageable = PageRequest.of(
                Math.max(0, page),
                Math.min(Math.max(1, size), MAX_PAGE_SIZE),
                sortOf(sortBy, direction));

        Page<Payment> result = paymentRepository.findAll(
                PaymentSpecifications.matching(filter, period.current(), search), pageable);

        Map<Long, BigDecimal> refunded = refundedByPayment(result.getContent());
        return PageResponseUtils.fromPage(result, p -> toTransaction(p, refunded.getOrDefault(p.getId(), BigDecimal.ZERO)));
    }

    @Override
    public PaymentTransactionDetailResponse transaction(Long paymentId) {
        Long restaurantId = restaurantId();

        Payment payment = paymentRepository.findById(paymentId)
                .filter(p -> p.getRestaurant().getId().equals(restaurantId))
                .orElseThrow(() -> new EntityNotFoundException("Payment not found."));

        Order order = payment.getOrder();
        List<Payment> siblings = paymentRepository.findByOrderIdOrderByIdAsc(order.getId());
        Map<Long, BigDecimal> refunded = refundedByPayment(siblings);

        List<PaymentTransactionDetailResponse.Refund> refunds = refundRepository.findByPaymentId(paymentId).stream()
                .map(this::toRefund)
                .toList();

        List<PaymentTransactionResponse> others = siblings.stream()
                .filter(p -> !p.getId().equals(paymentId))
                .map(p -> toTransaction(p, refunded.getOrDefault(p.getId(), BigDecimal.ZERO)))
                .toList();

        return new PaymentTransactionDetailResponse(
                toTransaction(payment, refunded.getOrDefault(payment.getId(), BigDecimal.ZERO)),
                order.getTotal(),
                order.getRestaurantTable() == null ? null : order.getRestaurantTable().getTableName(),
                refunds,
                others);
    }

    // ------------------------------------------------------------------ export

    @Override
    public int exportCsv(AnalyticsQuery q, String search, Writer out) throws IOException {
        Long restaurantId = restaurantId();
        ResolvedPeriod period = resolve(q);
        PaymentFilter filter = filterOf(restaurantId, q);

        out.write("Transaction ID,Date & time,Order,Method,UPI app / wallet / card type,Amount,Status,"
                + "Refunded,Net,Recorded by\r\n");

        int written = 0;
        int page = 0;
        Sort sort = Sort.by(Sort.Direction.DESC, "paymentTimestamp").and(Sort.by(Sort.Direction.DESC, "id"));

        while (written < MAX_EXPORT_ROWS) {
            Page<Payment> chunk = paymentRepository.findAll(
                    PaymentSpecifications.matching(filter, period.current(), search),
                    PageRequest.of(page, EXPORT_PAGE_SIZE, sort));
            Map<Long, BigDecimal> refunded = refundedByPayment(chunk.getContent());

            for (Payment p : chunk.getContent()) {
                if (written >= MAX_EXPORT_ROWS) {
                    break;
                }
                PaymentTransactionResponse t = toTransaction(p, refunded.getOrDefault(p.getId(), BigDecimal.ZERO));
                String instrument = t.providerLabel() != null ? t.providerLabel()
                        : (t.cardType() != null ? CardType.valueOf(t.cardType()).label() : "");
                boolean captured = p.getPaymentStatus().isCaptured();
                BigDecimal net = captured ? t.amount().subtract(t.refundedAmount()) : BigDecimal.ZERO;

                out.write(String.join(",",
                        text(t.reference()),
                        t.timestamp().format(CSV_TIME),
                        text(t.orderNumber()),
                        text(t.methodLabel()),
                        text(instrument),
                        t.amount().toPlainString(),
                        text(t.statusLabel()),
                        t.refundedAmount().toPlainString(),
                        net.toPlainString(),
                        text(t.recordedBy())));
                out.write("\r\n");
                written++;
            }
            if (!chunk.hasNext()) {
                break;
            }
            page++;
        }
        out.flush();
        return written;
    }

    // ------------------------------------------------------------------ helpers

    private Long restaurantId() {
        Restaurant restaurant = currentUserService.getCurrentRestaurant();
        if (restaurant == null) {
            throw new AccessDeniedException("This account is not attached to a restaurant.");
        }
        return restaurant.getId();
    }

    private ResolvedPeriod resolve(AnalyticsQuery q) {
        return PeriodResolver.resolve(
                q.preset() == null ? com.kashmirmenu.app.analytics.PeriodPreset.LAST_7_DAYS : q.preset(),
                q.from(), q.to(), businessTime.now());
    }

    private static PaymentFilter filterOf(Long restaurantId, AnalyticsQuery q) {
        return PaymentFilter.of(restaurantId, q.methods(), q.providers(), q.statuses(), q.staffId());
    }

    private static Sort sortOf(String sortBy, String direction) {
        String field = SORT_FIELDS.get(sortBy == null ? "time" : sortBy);
        if (field == null) {
            throw new IllegalArgumentException("Cannot sort by '" + sortBy + "'.");
        }
        Sort.Direction dir = "asc".equalsIgnoreCase(direction) ? Sort.Direction.ASC : Sort.Direction.DESC;
        // The id tie-break keeps page boundaries stable when many payments share a timestamp.
        return Sort.by(dir, field).and(Sort.by(dir, "id"));
    }

    private Map<Long, BigDecimal> refundedByPayment(List<Payment> payments) {
        Map<Long, BigDecimal> out = new HashMap<>();
        if (payments.isEmpty()) {
            return out;
        }
        List<Long> ids = payments.stream().map(Payment::getId).toList();
        for (Object[] row : refundRepository.sumByPaymentIds(ids)) {
            out.put((Long) row[0], (BigDecimal) row[1]);
        }
        return out;
    }

    private PaymentTransactionResponse toTransaction(Payment p, BigDecimal refunded) {
        PaymentMethod method = p.getPaymentMethod();
        PaymentProvider provider = p.getPaymentProvider();

        String providerKey = null;
        String providerLabel = null;
        if (method.hasProviders()) {
            if (provider != null) {
                providerKey = provider.name();
                providerLabel = provider.label();
            } else {
                providerKey = method == PaymentMethod.UPI ? AnalyticsCalculator.UNKNOWN_UPI : AnalyticsCalculator.UNKNOWN_WALLET;
                providerLabel = method == PaymentMethod.UPI ? "Unknown UPI app" : "Unknown wallet";
            }
        }

        boolean hasReference = p.getTransactionId() != null && !p.getTransactionId().isBlank();

        return new PaymentTransactionResponse(
                p.getId(),
                hasReference ? p.getTransactionId() : "PAY-" + p.getId(),
                hasReference,
                p.getOrder().getId(),
                p.getOrder().getOrderNumber(),
                method.name(),
                method.label(),
                providerKey,
                providerLabel,
                p.getCardType() == null ? null : p.getCardType().name(),
                p.getAmount(),
                refunded == null ? BigDecimal.ZERO : refunded,
                p.getPaymentStatus().name(),
                p.getPaymentStatus().label(),
                p.getPaymentTimestamp(),
                p.getRecordedBy() == null ? null : p.getRecordedBy().getName(),
                p.getSettledAt(),
                p.getCurrency());
    }

    private PaymentTransactionDetailResponse.Refund toRefund(PaymentRefund r) {
        return new PaymentTransactionDetailResponse.Refund(
                r.getId(),
                r.getAmount(),
                r.getReason(),
                r.getRefundedAt(),
                r.getRefundedBy() == null ? null : r.getRefundedBy().getName());
    }

    /**
     * CSV cell. A leading =, +, - or @ would be run as a formula by Excel and Sheets, so text cells that
     * start with one get a leading apostrophe (the OWASP guidance for CSV injection).
     */
    static String text(String value) {
        if (value == null) {
            return "";
        }
        String s = value;
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) {
            s = "'" + s;
        }
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
