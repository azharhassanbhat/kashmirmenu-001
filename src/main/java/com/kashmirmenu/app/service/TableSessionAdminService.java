package com.kashmirmenu.app.service;

import com.kashmirmenu.app.analytics.BillMath;
import com.kashmirmenu.app.analytics.MoneyMath;
import com.kashmirmenu.app.analytics.PaymentBalance;
import com.kashmirmenu.app.audit.AuditAction;
import com.kashmirmenu.app.audit.AuditService;
import com.kashmirmenu.app.config.BillingProperties;
import com.kashmirmenu.app.config.BusinessTime;
import com.kashmirmenu.app.config.TableSessionProperties;
import com.kashmirmenu.app.dto.response.BillItemResponse;
import com.kashmirmenu.app.dto.response.TableSessionDetailResponse;
import com.kashmirmenu.app.dto.response.TableSessionSummaryResponse;
import com.kashmirmenu.app.dto.response.VisitGuestResponse;
import com.kashmirmenu.app.dto.response.VisitOrderResponse;
import com.kashmirmenu.app.dto.response.VisitPaymentResponse;
import com.kashmirmenu.app.entity.DinerSession;
import com.kashmirmenu.app.entity.Order;
import com.kashmirmenu.app.entity.Payment;
import com.kashmirmenu.app.entity.Restaurant;
import com.kashmirmenu.app.entity.RestaurantTable;
import com.kashmirmenu.app.entity.TableSession;
import com.kashmirmenu.app.enums.TableSessionStatus;
import com.kashmirmenu.app.exception.ConflictException;
import com.kashmirmenu.app.metrics.FloorMetrics;
import com.kashmirmenu.app.exception.ResourceNotFoundException;
import com.kashmirmenu.app.repository.CartRepository;
import com.kashmirmenu.app.repository.DinerSessionRepository;
import com.kashmirmenu.app.repository.OrderRepository;
import com.kashmirmenu.app.repository.PaymentRepository;
import com.kashmirmenu.app.repository.RestaurantTableRepository;
import com.kashmirmenu.app.repository.TableSessionRepository;
import com.kashmirmenu.app.security.service.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The owner's view of the floor: which tables are open, who is at them, what each guest has ordered and
 * owes, how it was paid, and closing or reopening a table.
 *
 * <p>Every figure is worked out from the same order and payment records the guest screens and the staff
 * payment code use (PaymentService.getBalances through BillMath), so the dashboard cannot disagree with
 * them, and each payment is counted once.
 *
 * <p>Closing is never blocked by money still owing. Orders and payments stay exactly as they are, and a
 * payment waiting for staff can still be confirmed after the table is closed; only the guests' access ends.
 *
 * <p>Locks: closing locks the visit row alone. Reopening locks the table row first and then the visit row,
 * the same order a guest joining uses, so a reopen cannot slip in beside a join and leave two open visits.
 */
@Service
@RequiredArgsConstructor
public class TableSessionAdminService {

    public static final String CLOSED_BY_STAFF = "STAFF";
    public static final String CLOSED_IDLE = "IDLE_EXPIRED";

    private static final int RECENTLY_CLOSED_LIMIT = 20;

    private final TableSessionRepository tableSessionRepository;
    private final DinerSessionRepository dinerSessionRepository;
    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final CartRepository cartRepository;
    private final RestaurantTableRepository restaurantTableRepository;
    private final PaymentService paymentService;
    private final SettlementService settlementService;
    private final CurrentUserService currentUserService;
    private final BillingProperties billing;
    private final TableSessionProperties sessionProperties;
    private final BusinessTime businessTime;
    private final AuditService auditService;
    private final FloorMetrics metrics;

    private record Activity(int guests, LocalDateTime lastSeen) {
    }

    // ================================================================== reading

    @Transactional(readOnly = true)
    public List<TableSessionSummaryResponse> active() {
        Restaurant restaurant = currentUserService.getCurrentRestaurant();
        return summarise(tableSessionRepository.findWithTable(restaurant, TableSessionStatus.OPEN));
    }

    /** Tables closed in the recent past, newest first, so one closed by mistake can be found and reopened. */
    @Transactional(readOnly = true)
    public List<TableSessionSummaryResponse> recentlyClosed() {
        Restaurant restaurant = currentUserService.getCurrentRestaurant();
        LocalDateTime since = businessTime.now().minus(sessionProperties.getRecentlyClosedWindow());
        return summarise(tableSessionRepository.findClosedSince(
                restaurant, since, PageRequest.of(0, RECENTLY_CLOSED_LIMIT)));
    }

    @Transactional(readOnly = true)
    public TableSessionDetailResponse detail(Long id) {
        return build(requireVisit(id));
    }

    // ================================================================== closing and reopening

    /**
     * Closes a table. Allowed whatever is owed: nothing is deleted or settled, the guests simply can no
     * longer use their session. Their unsubmitted carts are withdrawn. Closing twice changes nothing.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TableSessionDetailResponse close(Long id) {

        Restaurant restaurant = currentUserService.getCurrentRestaurant();
        TableSession visit = tableSessionRepository.findByIdForUpdate(id)
                .filter(v -> v.getRestaurant().getId().equals(restaurant.getId()))
                .orElseThrow(() -> new ResourceNotFoundException("Table session not found."));

        if (visit.getStatus() == TableSessionStatus.OPEN) {
            BillMath.Amounts owing = totalsOf(visit);

            shut(visit, CLOSED_BY_STAFF, currentUserService.getCurrentUser());

            metrics.tableClosed(CLOSED_BY_STAFF);
            auditService.record(AuditAction.TABLE_SESSION_CLOSED, "TableSession", visit.getId(),
                    "Table " + visit.getRestaurantTable().getTableName() + " closed" + owingNote(owing));
        }
        return build(visit);
    }

    /**
     * Opens a closed visit again, which also gives its guests their sessions back. Refused if the table has
     * since started a new visit: there is only ever one open visit per table.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TableSessionDetailResponse reopen(Long id) {

        Restaurant restaurant = currentUserService.getCurrentRestaurant();
        Long tableId = tableSessionRepository.findTableIdOf(id, restaurant)
                .orElseThrow(() -> new ResourceNotFoundException("Table session not found."));

        // The table row first, as a guest joining does, so a reopen cannot slip in beside a join. The
        // visit itself is loaded only now, under its lock: a copy loaded earlier would not be refreshed.
        RestaurantTable table = restaurantTableRepository.findByIdForUpdate(tableId)
                .orElseThrow(() -> new ResourceNotFoundException("Table not found."));
        TableSession visit = tableSessionRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Table session not found."));

        if (visit.getStatus() == TableSessionStatus.CLOSED) {
            if (tableSessionRepository.findByRestaurantTableAndStatus(table, TableSessionStatus.OPEN).isPresent()) {
                throw new ConflictException(
                        "This table already has an open session. Close it before reopening an earlier one.");
            }
            visit.setStatus(TableSessionStatus.OPEN);
            visit.setClosedAt(null);
            visit.setClosedBy(null);
            visit.setCloseReason(null);

            auditService.record(AuditAction.TABLE_SESSION_REOPENED, "TableSession", visit.getId(),
                    "Table " + table.getTableName() + " reopened");
        }
        return build(visit);
    }

    /**
     * Closes a visit that has been idle and owes nothing. Called by the scheduled expiry, one visit per
     * transaction. Idleness and the bill are both checked again under the visit's lock, so a guest who
     * has just acted, or an order placed a moment ago, keeps the table open.
     *
     * <p>A visit that still owes money is never closed here, however idle: it is flagged for staff.
     *
     * @return true if it was closed
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean closeIfIdleAndSettled(Long visitId, LocalDateTime cutoff) {

        TableSession visit = tableSessionRepository.findByIdForUpdate(visitId).orElse(null);
        if (visit == null || visit.getStatus() != TableSessionStatus.OPEN) {
            return false;
        }
        Map<Long, Activity> activity = activityOf(List.of(visit));
        Activity a = activity.get(visit.getId());
        if (a != null && a.lastSeen() != null && a.lastSeen().isAfter(cutoff)) {
            return false;
        }
        BillMath.Amounts owing = totalsOf(visit);
        if (owing.due().signum() > 0 || owing.inProgress().signum() > 0 || owing.awaitingConfirmation().signum() > 0) {
            return false;
        }

        shut(visit, CLOSED_IDLE, null);

        metrics.tableClosed(CLOSED_IDLE);
        auditService.record(AuditAction.TABLE_SESSION_EXPIRED, "TableSession", visit.getId(),
                "Table " + visit.getRestaurantTable().getTableName() + " closed after being idle with nothing owing");
        return true;
    }

    // ================================================================== helpers

    private void shut(TableSession visit, String reason, com.kashmirmenu.app.entity.User by) {
        visit.setStatus(TableSessionStatus.CLOSED);
        visit.setClosedAt(businessTime.now());
        visit.setClosedBy(by);
        visit.setCloseReason(reason);
        cartRepository.cancelActiveCarts(visit);
    }

    private static String owingNote(BillMath.Amounts a) {
        BigDecimal owed = a.due().add(a.inProgress()).add(a.awaitingConfirmation());
        return owed.signum() > 0 ? " with " + owed.toPlainString() + " still owing" : "";
    }

    private TableSession requireVisit(Long id) {
        Restaurant restaurant = currentUserService.getCurrentRestaurant();
        return tableSessionRepository.findByIdAndRestaurant(id, restaurant)
                .orElseThrow(() -> new ResourceNotFoundException("Table session not found."));
    }

    private BillMath.Amounts totalsOf(TableSession visit) {
        List<Order> orders = orderRepository.findByTableSessionOrderByIdAsc(visit);
        Map<Long, PaymentBalance.Result> balances = paymentService.getBalances(orders);
        List<BillMath.OrderFacts> facts = new ArrayList<>();
        for (Order order : orders) {
            if (order.getDinerSession() != null) {
                facts.add(factsOf(order, balances.get(order.getId())));
            }
        }
        return BillMath.total(facts, billing.isRequireConfirmedOrders());
    }

    private static BillMath.OrderFacts factsOf(Order order, PaymentBalance.Result balance) {
        return new BillMath.OrderFacts(
                order.getDinerSession().getId(),
                order.getDinerName() == null ? "Guest" : order.getDinerName(),
                order.getStatus(),
                balance == null ? BigDecimal.ZERO : balance.settled(),
                balance == null ? BigDecimal.ZERO : balance.pending(),
                balance == null ? order.getTotal() : balance.outstanding());
    }

    private Map<Long, Activity> activityOf(List<TableSession> visits) {
        Map<Long, Activity> result = new HashMap<>();
        for (Object[] row : dinerSessionRepository.activityOf(visits)) {
            result.put((Long) row[0], new Activity(((Number) row[1]).intValue(), (LocalDateTime) row[2]));
        }
        return result;
    }

    /** Summaries for a list of visits in a fixed number of queries, however many tables there are. */
    private List<TableSessionSummaryResponse> summarise(List<TableSession> visits) {
        if (visits.isEmpty()) {
            return List.of();
        }
        List<Order> orders = orderRepository.findByTableSessionInOrderByIdAsc(visits);
        Map<Long, PaymentBalance.Result> balances = paymentService.getBalances(orders);

        Map<Long, List<BillMath.OrderFacts>> factsByVisit = new HashMap<>();
        for (Order order : orders) {
            if (order.getDinerSession() == null) {
                continue;
            }
            factsByVisit.computeIfAbsent(order.getTableSession().getId(), k -> new ArrayList<>())
                    .add(factsOf(order, balances.get(order.getId())));
        }

        Map<Long, Activity> activity = activityOf(visits);
        LocalDateTime idleBefore = businessTime.now().minus(sessionProperties.getIdleTimeout());

        List<TableSessionSummaryResponse> result = new ArrayList<>();
        for (TableSession visit : visits) {
            result.add(summaryOf(visit, factsByVisit.getOrDefault(visit.getId(), List.of()),
                    activity.get(visit.getId()), idleBefore));
        }
        return result;
    }

    private TableSessionSummaryResponse summaryOf(
            TableSession visit, List<BillMath.OrderFacts> facts, Activity activity, LocalDateTime idleBefore) {

        BillMath.Amounts a = BillMath.total(facts, billing.isRequireConfirmedOrders());
        LocalDateTime last = activity == null || activity.lastSeen() == null ? visit.getOpenedAt() : activity.lastSeen();

        boolean open = visit.getStatus() == TableSessionStatus.OPEN;
        boolean idle = open && last.isBefore(idleBefore);
        boolean owing = a.due().signum() > 0 || a.inProgress().signum() > 0 || a.awaitingConfirmation().signum() > 0;

        return new TableSessionSummaryResponse(
                visit.getId(),
                visit.getVisitCode(),
                visit.getRestaurantTable().getTableName(),
                visit.getStatus(),
                visit.getOpenedAt(),
                last,
                activity == null ? 0 : activity.guests(),
                a.due(),
                a.inProgress(),
                a.paid(),
                a.awaitingConfirmation(),
                BillMath.payState(a),
                idle,
                idle && owing,
                visit.getClosedAt(),
                visit.getCloseReason());
    }

    private TableSessionDetailResponse build(TableSession visit) {

        List<DinerSession> guests = dinerSessionRepository.findByTableSessionOrderByJoinNumberAsc(visit);
        List<Order> orders = orderRepository.findByTableSessionOrderByIdAsc(visit);
        Map<Long, PaymentBalance.Result> balances = paymentService.getBalances(orders);
        boolean require = billing.isRequireConfirmedOrders();

        Map<Long, Order> withItems = new HashMap<>();
        if (!orders.isEmpty()) {
            for (Order loaded : orderRepository.findWithItemsByIdIn(orders.stream().map(Order::getId).toList())) {
                withItems.put(loaded.getId(), loaded);
            }
        }

        // The orders of each guest, in the order they were placed.
        Map<Long, List<Order>> ordersByGuest = new LinkedHashMap<>();
        List<BillMath.OrderFacts> allFacts = new ArrayList<>();
        for (Order order : orders) {
            if (order.getDinerSession() == null) {
                continue;
            }
            ordersByGuest.computeIfAbsent(order.getDinerSession().getId(), k -> new ArrayList<>()).add(order);
            allFacts.add(factsOf(order, balances.get(order.getId())));
        }

        List<VisitGuestResponse> guestViews = new ArrayList<>();
        for (DinerSession guest : guests) {
            List<VisitOrderResponse> orderViews = new ArrayList<>();
            List<BillMath.OrderFacts> guestFacts = new ArrayList<>();

            for (Order order : ordersByGuest.getOrDefault(guest.getId(), List.of())) {
                BillMath.OrderFacts fact = factsOf(order, balances.get(order.getId()));
                BillMath.Amounts amounts = BillMath.of(fact, require);
                guestFacts.add(fact);

                List<BillItemResponse> items = withItems.getOrDefault(order.getId(), order).getItems().stream()
                        .map(i -> new BillItemResponse(i.getItemName(), i.getQuantity(), i.getUnitPrice(), i.getLineTotal()))
                        .toList();

                orderViews.add(new VisitOrderResponse(
                        order.getId(),
                        order.getOrderNumber(),
                        order.getStatus(),
                        order.getCreatedAt(),
                        MoneyMath.money(order.getTotal()),
                        amounts.paid(),
                        amounts.inProgress(),
                        amounts.due(),
                        amounts.awaitingConfirmation(),
                        BillMath.payState(amounts),
                        items));
            }

            BillMath.Amounts totals = BillMath.total(guestFacts, require);
            guestViews.add(new VisitGuestResponse(
                    guest.getId(),
                    guest.getDisplayName(),
                    guest.getCreatedAt(),
                    guest.getLastSeenAt(),
                    totals.due(),
                    totals.inProgress(),
                    totals.paid(),
                    totals.awaitingConfirmation(),
                    BillMath.payState(totals),
                    orderViews));
        }

        List<VisitPaymentResponse> payments = new ArrayList<>();
        if (!orders.isEmpty()) {
            List<Long> orderIds = orders.stream().map(Order::getId).toList();
            for (Payment p : paymentRepository.findForOrdersWithSettlement(orderIds)) {
                payments.add(new VisitPaymentResponse(
                        p.getId(),
                        p.getOrder().getOrderNumber(),
                        p.getOrder().getDinerName() == null ? "Guest" : p.getOrder().getDinerName(),
                        p.getPaymentMethod(),
                        p.getPaymentProvider(),
                        MoneyMath.money(p.getAmount()),
                        p.getPaymentStatus(),
                        p.getSettlement() == null ? null : p.getSettlement().getKind(),
                        p.getSettlement() == null ? null : p.getSettlement().getCode(),
                        p.getTransactionId(),
                        p.getPaidAt() != null ? p.getPaidAt() : p.getPaymentTimestamp()));
            }
        }

        LocalDateTime idleBefore = businessTime.now().minus(sessionProperties.getIdleTimeout());
        TableSessionSummaryResponse summary = summaryOf(
                visit, allFacts, activityOf(List.of(visit)).get(visit.getId()), idleBefore);

        return new TableSessionDetailResponse(
                summary,
                visit.getClosedBy() == null ? null : visit.getClosedBy().getName(),
                guestViews,
                settlementService.forVisit(visit),
                payments);
    }
}
