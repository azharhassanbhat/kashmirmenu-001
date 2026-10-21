package com.kashmirmenu.app.controller;

import com.kashmirmenu.app.analytics.AnalyticsOverview;
import com.kashmirmenu.app.analytics.AnalyticsQuery;
import com.kashmirmenu.app.analytics.Granularity;
import com.kashmirmenu.app.analytics.PeriodPreset;
import com.kashmirmenu.app.common.response.PageResponse;
import com.kashmirmenu.app.config.BusinessTime;
import com.kashmirmenu.app.dto.request.RecordCashCountRequest;
import com.kashmirmenu.app.dto.response.PaymentMetaResponse;
import com.kashmirmenu.app.dto.response.PaymentTransactionDetailResponse;
import com.kashmirmenu.app.dto.response.PaymentTransactionResponse;
import com.kashmirmenu.app.dto.response.ReconciliationResponse;
import com.kashmirmenu.app.enums.PaymentMethod;
import com.kashmirmenu.app.enums.PaymentStatus;
import com.kashmirmenu.app.service.PaymentAnalyticsService;
import com.kashmirmenu.app.service.ReconciliationService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Financial reporting. Everything here is restricted to owners, managers and admins and is scoped to the
 * caller's own restaurant by the services; cashiers and waiters can take payments but not read these.
 */
@RestController
@RequestMapping("/api/v1/payment-analytics")
@PreAuthorize("hasAnyRole('ADMIN','RESTAURANT_OWNER','MANAGER')")
@RequiredArgsConstructor
public class PaymentAnalyticsController {

    private final PaymentAnalyticsService analyticsService;
    private final ReconciliationService reconciliationService;
    private final BusinessTime businessTime;

    @GetMapping("/meta")
    @Operation(summary = "Payment methods, providers, statuses, staff and time zone for the dashboard")
    public PaymentMetaResponse meta() {
        return analyticsService.meta();
    }

    /**
     * Query parameters (all optional): period (TODAY, YESTERDAY, LAST_7_DAYS, LAST_30_DAYS, THIS_MONTH,
     * LAST_MONTH, CUSTOM), from/to (yyyy-MM-dd, for CUSTOM), granularity, methods, providers, statuses
     * (comma-separated), staffId.
     */
    @GetMapping("/overview")
    @Operation(summary = "KPIs, method and provider breakdown, time series, peak hours and insights")
    public AnalyticsOverview overview(@RequestParam Map<String, String> params) {
        return analyticsService.overview(parse(params));
    }

    @GetMapping("/transactions")
    @Operation(summary = "Paginated, filterable, sortable payment history")
    public PageResponse<PaymentTransactionResponse> transactions(@RequestParam Map<String, String> params) {
        return analyticsService.transactions(
                parse(params),
                params.get("search"),
                integer(params, "page", 0),
                integer(params, "size", 20),
                params.getOrDefault("sortBy", "time"),
                params.getOrDefault("direction", "desc"));
    }

    @GetMapping("/transactions/export")
    @Operation(summary = "The same filtered transactions as a CSV file")
    public ResponseEntity<byte[]> export(@RequestParam Map<String, String> params) throws IOException {
        // Built in memory (capped at the service's row limit) so a failure produces a normal error
        // response instead of a half-written download.
        StringWriter csv = new StringWriter();
        analyticsService.exportCsv(parse(params), params.get("search"), csv);

        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"payments-"
                        + businessTime.today() + ".csv\"")
                .body(csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    @GetMapping("/transactions/{paymentId}")
    @Operation(summary = "One payment with its refunds and the other payments on the same order")
    public PaymentTransactionDetailResponse transaction(@PathVariable Long paymentId) {
        return analyticsService.transaction(paymentId);
    }

    @GetMapping("/reconciliation")
    @Operation(summary = "Cash and payment reconciliation for one business day (default: today)")
    public ReconciliationResponse reconciliation(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return reconciliationService.forDate(date == null ? businessTime.today() : date);
    }

    @PostMapping("/reconciliation/counts")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Record a physical cash count; the expected amount is computed on the server")
    public ReconciliationResponse recordCount(@Valid @RequestBody RecordCashCountRequest request) {
        return reconciliationService.recordCount(request);
    }

    @PostMapping("/reconciliation/settle")
    @Operation(summary = "Mark the day's collected digital payments as settled into the bank")
    public ReconciliationResponse settle(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return reconciliationService.markDigitalSettled(date);
    }

    // ------------------------------------------------------------------ parameter parsing

    /** Every parse failure is an IllegalArgumentException, which the global handler turns into a 400. */
    private static AnalyticsQuery parse(Map<String, String> p) {
        return new AnalyticsQuery(
                enumValue(p, "period", PeriodPreset::valueOf, PeriodPreset.LAST_7_DAYS),
                date(p, "from"),
                date(p, "to"),
                enumValue(p, "granularity", Granularity::valueOf, null),
                list(p, "methods", PaymentMethod::valueOf),
                list(p, "providers", s -> s),
                list(p, "statuses", PaymentStatus::valueOf),
                longValue(p, "staffId"));
    }

    private static <T> T enumValue(Map<String, String> p, String name, Function<String, T> parser, T fallback) {
        String raw = p.get(name);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return parser.apply(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid value for '" + name + "'.");
        }
    }

    private static <T> List<T> list(Map<String, String> p, String name, Function<String, T> parser) {
        String raw = p.get(name);
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return Arrays.stream(raw.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(s -> parser.apply(s.toUpperCase()))
                    .toList();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid value for '" + name + "'.");
        }
    }

    private static LocalDate date(Map<String, String> p, String name) {
        String raw = p.get(name);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid value for '" + name + "'. Use yyyy-MM-dd.");
        }
    }

    private static Long longValue(Map<String, String> p, String name) {
        String raw = p.get(name);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid value for '" + name + "'.");
        }
    }

    private static int integer(Map<String, String> p, String name, int fallback) {
        String raw = p.get(name);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid value for '" + name + "'.");
        }
    }
}
