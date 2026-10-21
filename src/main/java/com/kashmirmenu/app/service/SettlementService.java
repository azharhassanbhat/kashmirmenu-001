package com.kashmirmenu.app.service;

import com.kashmirmenu.app.analytics.MoneyMath;
import com.kashmirmenu.app.analytics.PaymentBalance;
import com.kashmirmenu.app.analytics.SettlementRules;
import com.kashmirmenu.app.config.BillingProperties;
import com.kashmirmenu.app.dto.request.CreateSettlementRequest;
import com.kashmirmenu.app.dto.request.UpdatePaymentStatusRequest;
import com.kashmirmenu.app.dto.request.UpdateSettlementStatusRequest;
import com.kashmirmenu.app.dto.response.SettlementGuestLine;
import com.kashmirmenu.app.dto.response.SettlementOrderLine;
import com.kashmirmenu.app.dto.response.SettlementResponse;
import com.kashmirmenu.app.dto.response.SettlementStaffResponse;
import com.kashmirmenu.app.entity.DinerSession;
import com.kashmirmenu.app.entity.Order;
import com.kashmirmenu.app.entity.Payment;
import com.kashmirmenu.app.entity.Restaurant;
import com.kashmirmenu.app.entity.Settlement;
import com.kashmirmenu.app.entity.TableSession;
import com.kashmirmenu.app.enums.PaymentMethod;
import com.kashmirmenu.app.enums.PaymentStatus;
import com.kashmirmenu.app.enums.SettlementKind;
import com.kashmirmenu.app.enums.SettlementStatus;
import com.kashmirmenu.app.enums.TableSessionStatus;
import com.kashmirmenu.app.exception.BadRequestException;
import com.kashmirmenu.app.exception.ConflictException;
import com.kashmirmenu.app.exception.ResourceNotFoundException;
import com.kashmirmenu.app.repository.DinerSessionRepository;
import com.kashmirmenu.app.repository.OrderRepository;
import com.kashmirmenu.app.repository.PaymentRepository;
import com.kashmirmenu.app.repository.SettlementRepository;
import com.kashmirmenu.app.security.service.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Paying at a table: a guest settles their own orders (SEPARATE) or the whole table's (COMBINED).
 *
 * <p>A settlement is made of ordinary payments rows, one per covered order, each for that order's
 * outstanding amount at the moment it started. Nothing is paid until staff confirm it, and confirming
 * goes through the existing payment-status code, so notifications, audit fields and reports behave as
 * they always have.
 *
 * <h3>Why a settlement cannot charge anything twice</h3>
 * Every order it covers is locked (in ascending id order) and its balance worked out under that lock.
 * An order that already has money received or a payment in progress has nothing left to pay, so it is
 * simply not covered. Two guests starting a combined payment at once therefore queue on the first order
 * lock; the second finds those orders already taken and is refused. A retried request with the same
 * Idempotency-Key returns the settlement it already made.
 *
 * <h3>Lock order</h3>
 * paying guest's row, then orders by ascending id, then (for confirming) payments by ascending id.
 * Nothing here takes the table row, and nothing takes an order lock while holding a payment lock, so
 * it fits the order documented on RestaurantTableRepository and cannot form a cycle.
 *
 * <h3>Why these methods run at READ COMMITTED</h3>
 * MySQL's default, REPEATABLE READ, fixes a transaction's snapshot at its first plain read. Looking the
 * guest up is a plain read, and it happens BEFORE the transaction waits for an order lock. After the
 * wait, the payments read for that order would come from the older snapshot and miss the payment the
 * other transaction has just committed, so two simultaneous requests could both pass the "nothing paid
 * yet" check and charge an order twice. At READ COMMITTED every statement sees what has been committed
 * by then, so the check made under the lock is true. H2, which the tests use, already behaves this way,
 * so the tests alone cannot show this; do not remove the isolation level without a MySQL test.
 *
 * <h3>No write to the shared visit row</h3>
 * Inserting a settlement or payment takes a shared lock on the visit's row (its foreign key). Updating
 * that row afterwards would need an exclusive lock, and two guests paying at once would each hold the
 * shared one and wait for the other: a deadlock MySQL resolves by aborting one of them. So nothing here
 * writes the visit row; a guest's activity is recorded on their own row when they make the request.
 *
 * <p>Orders placed after a settlement starts are not part of it. They stay outstanding on their own.
 */
@Service
@RequiredArgsConstructor
public class SettlementService {

    /** A UPI transaction reference: letters, digits and - _ / only, so it cannot carry markup or odd text. */
    private static final Pattern UPI_REFERENCE = Pattern.compile("^[A-Za-z0-9_/-]{6,35}$");

    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9._:\\-]{1,80}$");

    private final TableSessionService tableSessionService;
    private final DinerSessionRepository dinerSessionRepository;
    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final SettlementRepository settlementRepository;
    private final PaymentService paymentService;
    private final CurrentUserService currentUserService;
    private final BillingProperties billing;

    // ================================================================== guests

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public SettlementResponse create(
            String qr, String dinerToken, CreateSettlementRequest request, String rawIdempotencyKey) {

        DinerSession resolved = tableSessionService.resolve(qr, dinerToken);

        PaymentMethod method = request.paymentMethod();
        if (!method.isDinerSelectable()) {
            throw new BadRequestException(method.label() + " cannot be chosen here. Please ask a member of staff.");
        }
        String key = normaliseKey(rawIdempotencyKey);

        // The paying guest's row first: two requests from one phone (a double tap, a retry that
        // overtook the original) queue here, and the replay check below runs under it.
        DinerSession payer = lockGuest(resolved);
        TableSession visit = payer.getTableSession();

        if (key != null) {
            Optional<Settlement> earlier = settlementRepository.findByPayerAndIdempotencyKey(payer, key);
            if (earlier.isPresent()) {
                Settlement s = earlier.get();
                if (s.getKind() != request.kind() || s.getPaymentMethod() != method) {
                    throw new BadRequestException("This Idempotency-Key was already used for a different payment.");
                }
                return guestView(s, paymentsOf(List.of(s)).getOrDefault(s.getId(), List.of()), payer.getId());
            }
        }

        // Ids first, then each order locked and read for the first time under its lock.
        List<Long> ids = request.kind() == SettlementKind.SEPARATE
                ? orderRepository.findLockableIdsByDiner(payer)
                : orderRepository.findLockableIdsByVisit(visit);

        Map<Long, Order> locked = new HashMap<>();
        List<SettlementRules.Candidate> candidates = new ArrayList<>();

        for (Long id : ids) {
            Order order = orderRepository.findByIdForUpdate(id).orElse(null);
            if (order == null || order.getDinerSession() == null) {
                continue;
            }
            PaymentBalance.Result balance = paymentService.getBalance(order);
            locked.put(order.getId(), order);
            candidates.add(new SettlementRules.Candidate(
                    order.getId(), order.getDinerSession().getId(), order.getStatus(), balance.outstanding()));
        }

        SettlementRules.Plan plan = SettlementRules.plan(
                request.kind(), payer.getId(), candidates, billing.isRequireConfirmedOrders());

        if (plan.isEmpty()) {
            throw new ConflictException(nothingToPay(request.kind(), plan));
        }

        Settlement settlement = new Settlement();
        settlement.setRestaurant(visit.getRestaurant());
        settlement.setTableSession(visit);
        settlement.setPayer(payer);
        settlement.setKind(request.kind());
        settlement.setPaymentMethod(method);
        settlement.setTotal(plan.total());
        settlement.setIdempotencyKey(key);
        settlement = settlementRepository.save(settlement);

        List<Payment> payments = new ArrayList<>();
        for (SettlementRules.Line line : plan.lines()) {
            payments.add(paymentService.openPendingPayment(
                    locked.get(line.orderId()), method, line.amount(), settlement));
        }

        return guestView(settlement, payments, payer.getId());
    }

    @Transactional(readOnly = true)
    public SettlementResponse get(String qr, String dinerToken, String code) {

        DinerSession payer = tableSessionService.resolve(qr, dinerToken);
        Settlement s = ownSettlement(payer, code);

        return guestView(s, paymentsOf(List.of(s)).getOrDefault(s.getId(), List.of()), payer.getId());
    }

    /** The guest's own settlements, newest first: how a refreshed page finds a payment in progress. */
    @Transactional(readOnly = true)
    public List<SettlementResponse> list(String qr, String dinerToken) {

        DinerSession payer = tableSessionService.resolve(qr, dinerToken);
        List<Settlement> mine = settlementRepository.findByPayerOrderByIdDesc(payer);
        Map<Long, List<Payment>> payments = paymentsOf(mine);

        List<SettlementResponse> result = new ArrayList<>();
        for (Settlement s : mine) {
            result.add(guestView(s, payments.getOrDefault(s.getId(), List.of()), payer.getId()));
        }
        return result;
    }

    /**
     * The guest says they have paid by UPI, optionally giving the transaction reference. This is only a
     * claim: the payments stay PENDING (shown to the guest as "verification pending") until staff confirm
     * the money arrived. It never marks anything paid and the guest names no amount.
     *
     * <p>Idempotent: a second tap changes nothing and keeps the first claim, so a double tap or a retry
     * cannot overwrite the reference. Only a still-pending UPI settlement can be claimed.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public SettlementResponse claimPaid(String qr, String dinerToken, String code, String rawReference) {

        DinerSession resolved = tableSessionService.resolve(qr, dinerToken);
        DinerSession payer = lockGuest(resolved);
        Settlement s = ownSettlement(payer, code);

        if (s.getPaymentMethod() != PaymentMethod.UPI) {
            throw new BadRequestException("Only a UPI payment can be confirmed this way.");
        }
        String reference = normaliseReference(rawReference);

        List<Payment> payments = paymentsOf(List.of(s)).getOrDefault(s.getId(), List.of());
        if (statusOf(payments) != SettlementStatus.PENDING) {
            throw new ConflictException("This payment is no longer waiting for confirmation.");
        }

        if (s.getCustomerConfirmedAt() == null) {
            s.setCustomerConfirmedAt(LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
            s.setCustomerReference(reference);
            settlementRepository.save(s);
        }
        return guestView(s, payments, payer.getId());
    }

    private static String normaliseReference(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String ref = raw.trim();
        if (!UPI_REFERENCE.matcher(ref).matches()) {
            throw new BadRequestException(
                    "The transaction reference should be 6 to 35 letters or digits. You can leave it blank.");
        }
        return ref;
    }

    /** The paying guest withdraws a payment staff have not confirmed yet. The orders become payable again. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public SettlementResponse cancel(String qr, String dinerToken, String code) {

        DinerSession resolved = tableSessionService.resolve(qr, dinerToken);
        DinerSession payer = lockGuest(resolved);
        Settlement s = ownSettlement(payer, code);

        int cancelled = paymentService.cancelPendingPayments(s);

        List<Payment> payments = paymentsOf(List.of(s)).getOrDefault(s.getId(), List.of());
        SettlementStatus status = statusOf(payments);

        // Nothing was waiting. Fine if it was already withdrawn or never went through; not fine if
        // money was received, which a guest cannot take back.
        if (cancelled == 0 && (status == SettlementStatus.SUCCESS || status == SettlementStatus.PARTIAL)) {
            throw new ConflictException("This payment has already been received and cannot be cancelled.");
        }

        return guestView(s, payments, payer.getId());
    }

    // ================================================================== staff

    /** Settlements with a payment still waiting for staff to confirm, oldest first. */
    @Transactional(readOnly = true)
    public List<SettlementStaffResponse> awaitingStaff() {

        Restaurant restaurant = currentUserService.getCurrentRestaurant();
        List<Settlement> waiting = settlementRepository.findAwaitingStaff(restaurant);
        Map<Long, List<Payment>> payments = paymentsOf(waiting);

        List<SettlementStaffResponse> result = new ArrayList<>();
        for (Settlement s : waiting) {
            result.add(staffView(s, payments.getOrDefault(s.getId(), List.of())));
        }
        return result;
    }

    /**
     * Staff say the money arrived (SUCCESS) or did not (FAILED, CANCELLED). Applied to every payment in
     * the settlement that is still pending, through the ordinary payment-status code. Repeating the
     * same decision changes nothing; contradicting one already made is refused.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public SettlementStaffResponse updateStatus(Long settlementId, UpdateSettlementStatusRequest request) {

        Restaurant restaurant = currentUserService.getCurrentRestaurant();
        Settlement s = settlementRepository.findById(settlementId)
                .filter(x -> x.getRestaurant().getId().equals(restaurant.getId()))
                .orElseThrow(() -> new ResourceNotFoundException("Settlement not found."));

        PaymentStatus target = request.paymentStatus();
        if (target != PaymentStatus.SUCCESS && target != PaymentStatus.FAILED && target != PaymentStatus.CANCELLED) {
            throw new BadRequestException("A settlement can be marked SUCCESS, FAILED or CANCELLED.");
        }

        int changed = 0;
        for (Long paymentId : paymentRepository.findIdsBySettlement(s)) {
            Payment payment = paymentRepository.findByIdForUpdate(paymentId)
                    .orElseThrow(() -> new ResourceNotFoundException("Payment not found."));
            if (payment.getPaymentStatus() == PaymentStatus.PENDING) {
                UpdatePaymentStatusRequest one = new UpdatePaymentStatusRequest();
                one.setPaymentStatus(target);
                one.setTransactionId(request.transactionId());
                paymentService.updatePaymentStatus(paymentId, one);
                changed++;
            }
        }

        List<Payment> payments = paymentsOf(List.of(s)).getOrDefault(s.getId(), List.of());

        if (changed == 0 && !alreadyIn(target, statusOf(payments))) {
            throw new ConflictException("This payment is already "
                    + statusOf(payments).name().toLowerCase() + " and can no longer be changed.");
        }
        return staffView(s, payments);
    }

    /**
     * Every settlement of one visit, newest first, as staff see them. The caller has already checked the
     * visit belongs to the staff member's restaurant.
     */
    public List<SettlementStaffResponse> forVisit(TableSession visit) {

        List<Settlement> all = settlementRepository.findByTableSessionOrderByIdDesc(visit);
        Map<Long, List<Payment>> payments = paymentsOf(all);

        List<SettlementStaffResponse> result = new ArrayList<>();
        for (Settlement s : all) {
            result.add(staffView(s, payments.getOrDefault(s.getId(), List.of())));
        }
        return result;
    }

    // ================================================================== helpers

    private DinerSession lockGuest(DinerSession resolved) {
        DinerSession locked = dinerSessionRepository.findByIdForUpdate(resolved.getId())
                .orElseThrow(() -> new ResourceNotFoundException(TableSessionService.SESSION_GONE));
        if (locked.getTableSession().getStatus() != TableSessionStatus.OPEN) {
            throw new ResourceNotFoundException(TableSessionService.SESSION_GONE);
        }
        return locked;
    }

    /** A guest reaches only their own settlements; anyone else's looks exactly like a missing one. */
    private Settlement ownSettlement(DinerSession payer, String code) {
        return settlementRepository.findByCode(code)
                .filter(s -> s.getPayer().getId().equals(payer.getId()))
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found."));
    }

    private static String normaliseKey(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String key = raw.trim();
        if (!IDEMPOTENCY_KEY.matcher(key).matches()) {
            throw new BadRequestException("Idempotency-Key must be 1 to 80 letters, digits or . _ : -");
        }
        return key;
    }

    private static String nothingToPay(SettlementKind kind, SettlementRules.Plan plan) {
        String who = kind == SettlementKind.SEPARATE ? "your order" : "the table's orders";
        if (plan.awaitingConfirmation() > 0) {
            return "Nothing can be paid yet: " + who + " "
                    + (kind == SettlementKind.SEPARATE ? "is" : "are")
                    + " waiting for the kitchen to confirm.";
        }
        return kind == SettlementKind.SEPARATE
                ? "You have nothing left to pay. A payment may already be in progress or have been received."
                : "The table has nothing left to pay. A payment may already be in progress or have been received.";
    }

    private static boolean alreadyIn(PaymentStatus target, SettlementStatus status) {
        return switch (target) {
            case SUCCESS -> status == SettlementStatus.SUCCESS;
            case FAILED -> status == SettlementStatus.FAILED;
            case CANCELLED -> status == SettlementStatus.CANCELLED;
            default -> false;
        };
    }

    private static SettlementStatus statusOf(Collection<Payment> payments) {
        return SettlementRules.statusOf(payments.stream().map(Payment::getPaymentStatus).toList());
    }

    /** The payments of each settlement, in one query. */
    private Map<Long, List<Payment>> paymentsOf(Collection<Settlement> settlements) {
        Map<Long, List<Payment>> byId = new HashMap<>();
        if (settlements.isEmpty()) {
            return byId;
        }
        for (Payment p : paymentRepository.findBySettlements(settlements)) {
            byId.computeIfAbsent(p.getSettlement().getId(), k -> new ArrayList<>()).add(p);
        }
        return byId;
    }

    private static BigDecimal sum(Collection<Payment> payments, java.util.function.Predicate<PaymentStatus> which) {
        BigDecimal total = BigDecimal.ZERO;
        for (Payment p : payments) {
            if (which.test(p.getPaymentStatus())) {
                total = total.add(p.getAmount());
            }
        }
        return MoneyMath.money(total);
    }

    /** The paying guest's view: names and amounts per guest, never anyone's items. */
    private SettlementResponse guestView(Settlement s, List<Payment> payments, long viewerId) {

        Map<Long, String> names = new LinkedHashMap<>();
        Map<Long, BigDecimal> amounts = new LinkedHashMap<>();
        for (Payment p : payments) {
            Order order = p.getOrder();
            long guestId = order.getDinerSession() == null ? -1L : order.getDinerSession().getId();
            names.putIfAbsent(guestId, order.getDinerName() == null ? "Guest" : order.getDinerName());
            amounts.merge(guestId, p.getAmount(), BigDecimal::add);
        }

        List<SettlementGuestLine> guests = new ArrayList<>();
        amounts.forEach((id, amount) ->
                guests.add(new SettlementGuestLine(names.get(id), id == viewerId, MoneyMath.money(amount))));

        // For the receipt: when the money was confirmed, and the reference staff gave when they did.
        LocalDateTime confirmedAt = null;
        String reference = null;
        for (Payment p : payments) {
            if (!p.getPaymentStatus().isCaptured()) {
                continue;
            }
            if (p.getPaidAt() != null && (confirmedAt == null || p.getPaidAt().isAfter(confirmedAt))) {
                confirmedAt = p.getPaidAt();
            }
            if (reference == null && p.getTransactionId() != null && !p.getTransactionId().isBlank()) {
                reference = p.getTransactionId();
            }
        }

        return new SettlementResponse(
                s.getCode(),
                s.getKind(),
                statusOf(payments),
                s.getPaymentMethod(),
                MoneyMath.money(s.getTotal()),
                sum(payments, st -> st == PaymentStatus.PENDING),
                sum(payments, PaymentStatus::isCaptured),
                s.getCreatedAt(),
                confirmedAt,
                reference,
                s.getCustomerConfirmedAt(),
                guests);
    }

    private SettlementStaffResponse staffView(Settlement s, List<Payment> payments) {

        List<SettlementOrderLine> orders = new ArrayList<>();
        for (Payment p : payments) {
            Order order = p.getOrder();
            orders.add(new SettlementOrderLine(
                    order.getOrderNumber(),
                    order.getDinerName() == null ? "Guest" : order.getDinerName(),
                    MoneyMath.money(p.getAmount()),
                    p.getPaymentStatus()));
        }

        return new SettlementStaffResponse(
                s.getId(),
                s.getCode(),
                s.getKind(),
                statusOf(payments),
                s.getTableSession().getRestaurantTable().getTableName(),
                s.getPayer().getDisplayName(),
                s.getPaymentMethod(),
                MoneyMath.money(s.getTotal()),
                sum(payments, st -> st == PaymentStatus.PENDING),
                sum(payments, PaymentStatus::isCaptured),
                s.getCreatedAt(),
                s.getCustomerConfirmedAt(),
                s.getCustomerReference(),
                orders);
    }
}
