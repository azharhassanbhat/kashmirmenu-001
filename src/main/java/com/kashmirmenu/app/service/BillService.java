package com.kashmirmenu.app.service;

import com.kashmirmenu.app.analytics.BillMath;
import com.kashmirmenu.app.analytics.MoneyMath;
import com.kashmirmenu.app.analytics.PaymentBalance;
import com.kashmirmenu.app.config.BillingProperties;
import com.kashmirmenu.app.dto.response.BillItemResponse;
import com.kashmirmenu.app.dto.response.BillOrderResponse;
import com.kashmirmenu.app.dto.response.DinerBillResponse;
import com.kashmirmenu.app.dto.response.TableBillGuestResponse;
import com.kashmirmenu.app.dto.response.TableBillResponse;
import com.kashmirmenu.app.entity.DinerSession;
import com.kashmirmenu.app.entity.Order;
import com.kashmirmenu.app.entity.TableSession;
import com.kashmirmenu.app.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only bills for a table visit: the calling guest's own ("my bill") and the whole table's
 * ("table bill"). Nothing here changes any order or payment.
 *
 * <p>Everything is keyed by the caller's guest token, so a guest only ever sees their own visit.
 * Viewing a bill is deliberately not counted as table activity: this service runs read-only, and the
 * "last seen" refresh that guest lookup normally does is skipped inside it.
 *
 * <p>The figures come from the payment service's own balances, not from a second calculation, so a
 * bill can never disagree with what staff see against the same order.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BillService {

    private final TableSessionService tableSessionService;
    private final OrderRepository orderRepository;
    private final PaymentService paymentService;
    private final BillingProperties billing;

    public DinerBillResponse myBill(String qr, String dinerToken) {

        DinerSession diner = tableSessionService.resolve(qr, dinerToken);
        TableSession visit = diner.getTableSession();

        List<Order> orders = orderRepository.findByDinerSessionOrderByIdAsc(diner);
        Map<Long, PaymentBalance.Result> balances = paymentService.getBalances(orders);
        Map<Long, Order> withItems = itemsOf(orders);

        List<BillMath.OrderFacts> facts = new ArrayList<>();
        List<BillOrderResponse> lines = new ArrayList<>();

        for (Order order : orders) {
            PaymentBalance.Result balance = balances.get(order.getId());
            BillMath.OrderFacts fact = factsOf(order, diner.getId(), diner.getDisplayName(), balance);
            BillMath.Amounts amounts = BillMath.of(fact, billing.isRequireConfirmedOrders());
            facts.add(fact);

            List<BillItemResponse> items = withItems.getOrDefault(order.getId(), order).getItems().stream()
                    .map(i -> new BillItemResponse(
                            i.getItemName(), i.getQuantity(), i.getUnitPrice(), i.getLineTotal()))
                    .toList();

            lines.add(new BillOrderResponse(
                    order.getOrderNumber(),
                    order.getPublicToken(),
                    order.getStatus(),
                    order.getCreatedAt(),
                    MoneyMath.money(order.getSubtotal()),
                    MoneyMath.money(order.getTax()),
                    MoneyMath.money(order.getServiceCharge()),
                    MoneyMath.money(order.getDiscount()),
                    MoneyMath.money(order.getTotal()),
                    amounts.paid(),
                    amounts.inProgress(),
                    amounts.due(),
                    amounts.awaitingConfirmation(),
                    balance == null ? null : balance.status(),
                    BillMath.isPayable(order.getStatus(), billing.isRequireConfirmedOrders())
                            && amounts.due().signum() > 0,
                    items));
        }

        BillMath.Amounts total = BillMath.total(facts, billing.isRequireConfirmedOrders());

        return new DinerBillResponse(
                diner.getDisplayName(),
                visit.getVisitCode(),
                lines,
                total.due(),
                total.inProgress(),
                total.paid(),
                total.awaitingConfirmation());
    }

    public TableBillResponse tableBill(String qr, String dinerToken) {

        DinerSession diner = tableSessionService.resolve(qr, dinerToken);
        TableSession visit = diner.getTableSession();

        // Every order of this visit, and only this visit: guests from an earlier visit to the same
        // table are never part of it.
        List<Order> orders = orderRepository.findByTableSessionOrderByIdAsc(visit);
        Map<Long, PaymentBalance.Result> balances = paymentService.getBalances(orders);

        List<BillMath.OrderFacts> facts = new ArrayList<>();
        for (Order order : orders) {
            // An order with a visit always has its guest; the check only guards against bad data.
            if (order.getDinerSession() == null) {
                continue;
            }
            facts.add(factsOf(
                    order,
                    order.getDinerSession().getId(),
                    order.getDinerName() == null ? "Guest" : order.getDinerName(),
                    balances.get(order.getId())));
        }

        List<TableBillGuestResponse> guests = BillMath.byGuest(facts, billing.isRequireConfirmedOrders()).stream()
                .map(g -> new TableBillGuestResponse(
                        g.guestName(),
                        g.guestId() == diner.getId(),
                        g.amounts().due(),
                        g.amounts().inProgress(),
                        g.amounts().awaitingConfirmation()))
                .toList();

        BillMath.Amounts total = BillMath.total(facts, billing.isRequireConfirmedOrders());

        return new TableBillResponse(
                visit.getVisitCode(),
                visit.getRestaurantTable().getTableName(),
                guests,
                total.due(),
                total.inProgress(),
                total.paid(),
                total.awaitingConfirmation());
    }

    private static BillMath.OrderFacts factsOf(
            Order order, long guestId, String guestName, PaymentBalance.Result balance) {

        return new BillMath.OrderFacts(
                guestId,
                guestName,
                order.getStatus(),
                balance == null ? BigDecimal.ZERO : balance.settled(),
                balance == null ? BigDecimal.ZERO : balance.pending(),
                balance == null ? order.getTotal() : balance.outstanding());
    }

    /** The orders' items in one query, so listing a guest's bill does not query once per order. */
    private Map<Long, Order> itemsOf(List<Order> orders) {
        Map<Long, Order> byId = new HashMap<>();
        if (orders.isEmpty()) {
            return byId;
        }
        for (Order loaded : orderRepository.findWithItemsByIdIn(orders.stream().map(Order::getId).toList())) {
            byId.put(loaded.getId(), loaded);
        }
        return byId;
    }
}
