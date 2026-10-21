package com.kashmirmenu.app.service.impl;

import com.kashmirmenu.app.service.OrderHistory;
import com.kashmirmenu.app.service.CancellationRules;
import com.kashmirmenu.app.repository.OrderStatusEventRepository;
import com.kashmirmenu.app.dto.response.OrderHistoryEntryResponse;
import com.kashmirmenu.app.entity.OrderItemOption;
import com.kashmirmenu.app.service.OrderCharges;
import com.kashmirmenu.app.entity.MenuOption;
import com.kashmirmenu.app.entity.CartItemOption;
import com.kashmirmenu.app.dto.request.CreateOrderRequest;
import com.kashmirmenu.app.dto.response.OrderResponse;
import com.kashmirmenu.app.entity.Cart;
import com.kashmirmenu.app.entity.DinerSession;
import com.kashmirmenu.app.entity.TableSession;
import com.kashmirmenu.app.enums.TableSessionStatus;
import com.kashmirmenu.app.repository.DinerSessionRepository;
import com.kashmirmenu.app.service.TableSessionService;
import com.kashmirmenu.app.entity.CartItem;
import com.kashmirmenu.app.entity.MenuItem;
import com.kashmirmenu.app.entity.Order;
import com.kashmirmenu.app.entity.OrderItem;
import com.kashmirmenu.app.entity.Restaurant;
import com.kashmirmenu.app.entity.RestaurantTable;
import com.kashmirmenu.app.enums.CartStatus;
import com.kashmirmenu.app.enums.NotificationType;
import com.kashmirmenu.app.enums.OrderStatus;
import com.kashmirmenu.app.exception.BadRequestException;
import com.kashmirmenu.app.exception.ResourceNotFoundException;
import com.kashmirmenu.app.mapper.OrderMapper;
import com.kashmirmenu.app.repository.CartItemRepository;
import com.kashmirmenu.app.common.response.PageResponse;
import com.kashmirmenu.app.analytics.PaymentBalance;
import com.kashmirmenu.app.coupon.CouponPricing;
import com.kashmirmenu.app.coupon.CouponTerms;
import com.kashmirmenu.app.config.BusinessTime;
import com.kashmirmenu.app.coupon.CouponPricing;
import com.kashmirmenu.app.entity.Coupon;
import com.kashmirmenu.app.repository.CartRepository;
import com.kashmirmenu.app.entity.RestaurantOrderCounter;
import com.kashmirmenu.app.repository.CouponRepository;
import com.kashmirmenu.app.repository.RestaurantOrderCounterRepository;
import com.kashmirmenu.app.repository.OrderRepository;
import com.kashmirmenu.app.repository.RestaurantTableRepository;
import com.kashmirmenu.app.security.service.CurrentUserService;
import com.kashmirmenu.app.service.NotificationService;
import com.kashmirmenu.app.service.OrderService;
import com.kashmirmenu.app.service.OrderStatusPolicy;
import com.kashmirmenu.app.service.PaymentService;
import com.kashmirmenu.app.util.PageResponseUtils;
import com.kashmirmenu.app.service.StockService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;

@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepository;
    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final RestaurantTableRepository restaurantTableRepository;
    private final DinerSessionRepository dinerSessionRepository;
    private final OrderMapper orderMapper;
    private final NotificationService notificationService;
    private final CurrentUserService currentUserService;
    private final CouponRepository couponRepository;
    private final BusinessTime businessTime;
    private final RestaurantOrderCounterRepository orderCounterRepository;
    private final StockService stockService;
    private final OrderHistory orderHistory;
    private final OrderStatusEventRepository statusEvents;
    private final PaymentService paymentService;

    /** Ceiling on page size, so a client cannot ask for the unbounded list this replaced. */
    private static final int MAX_PAGE_SIZE = 100;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @Override
    @Transactional
    public OrderResponse placeOrder(
            CreateOrderRequest request) {

        // Locks the table row FIRST, before reading the cart. Without it, two requests arriving
        // before either commits both see an ACTIVE cart and both place an order - which is what a
        // double-tap on a phone produces. The second now waits here, then finds the cart already
        // CHECKED_OUT below and is refused.
        //
        // LOCK ORDER (see RestaurantTableRepository): restaurant_tables -> coupons -> order counter
        // -> inventory. Keep it; reordering in one place is enough to deadlock two checkouts at
        // different tables against the shared inventory rows.
        RestaurantTable restaurantTable =
                restaurantTableRepository
                        .findByIdForUpdate(request.getRestaurantTableId())
                        .orElseThrow(() ->
                                new ResourceNotFoundException(
                                        "Restaurant table not found"));

        // Read after the lock is held, so this reflects any checkout that just committed. This is the
        // staff cart (no guest): guests check out through placeOrderForDiner, from their own carts.
        Cart cart =
                cartRepository
                        .findByRestaurantTableAndStatusAndDinerSessionIsNull(
                                restaurantTable,
                                CartStatus.ACTIVE
                        )
                        .orElseThrow(() ->
                                new BadRequestException(
                                        "No active cart found"));

        return checkout(restaurantTable, cart, null, request.getNotes());
    }

    @Override
    @Transactional
    public OrderResponse placeOrderForDiner(
            DinerSession diner,
            String tableQrIdentifier,
            String notes) {

        // The guest's own row is the lock, not the table's: a double-tap from one phone queues
        // here, and the second request then finds the cart already CHECKED_OUT and is refused,
        // while other guests at the same table check out at the same time without waiting.
        //
        // LOCK ORDER: guest -> coupons -> order counter -> inventory. The guest lock is taken
        // first and never while holding the table row's lock (join and the staff path take only the
        // table row), so it cannot form a cycle with the order documented on
        // RestaurantTableRepository.
        DinerSession locked = dinerSessionRepository
                .findByIdForUpdate(diner.getId())
                .orElseThrow(() -> new ResourceNotFoundException(TableSessionService.SESSION_GONE));

        TableSession visit = locked.getTableSession();

        if (visit.getStatus() != TableSessionStatus.OPEN
                || !visit.getRestaurantTable().getQrIdentifier().equals(tableQrIdentifier)) {
            throw new ResourceNotFoundException(TableSessionService.SESSION_GONE);
        }

        Cart cart =
                cartRepository
                        .findByDinerSessionAndStatus(locked, CartStatus.ACTIVE)
                        .orElseThrow(() ->
                                new BadRequestException(
                                        "No active cart found"));

        return checkout(visit.getRestaurantTable(), cart, locked, notes);
    }

    /**
     * Turns a cart into an order. The caller has already chosen and locked the cart's owner (the
     * table row for staff, the guest's row for a guest) and loaded the cart under that lock.
     *
     * @param diner the guest placing the order, or null for a staff order
     */
    private OrderResponse checkout(
            RestaurantTable restaurantTable,
            Cart cart,
            DinerSession diner,
            String notes) {

        Restaurant restaurant =
                restaurantTable.getRestaurant();

        if (cart.getItems() == null
                || cart.getItems().isEmpty()) {

            throw new BadRequestException(
                    "Cart is empty");
        }

        // Dishes are re-checked here, not only when they were added to the cart. An item switched
        // off after a diner added it would otherwise still reach the kitchen, which has no way to
        // make it. The price deliberately stays as captured when the item was added: the diner is
        // charged what they were shown.
        for (CartItem cartItem : cart.getItems()) {

            MenuItem menuItem = cartItem.getMenuItem();

            if (!Boolean.TRUE.equals(menuItem.getActive())
                    || !Boolean.TRUE.equals(menuItem.getAvailable())) {

                throw new BadRequestException(
                        menuItem.getName() + " is no longer available. Please remove it to continue.");
            }

            // The same for each option on the line. An add-on sold out since it was chosen, or one the
            // restaurant has deleted, would otherwise reach a kitchen that cannot make it.
            for (CartItemOption chosen : cartItem.getOptions()) {
                MenuOption live = chosen.getOption();
                if (live == null || !Boolean.TRUE.equals(live.getAvailable())) {
                    throw new BadRequestException(
                            chosen.getOptionName() + " on " + menuItem.getName()
                                    + " is no longer available. Please remove it to continue.");
                }
            }
        }

        // The coupon is re-evaluated as well as claimed. recalculateTotals only runs when the cart
        // changes, so a cart left untouched across the coupon's expiry kept its discount - and
        // claimOneUse checks the usage limit alone, not the dates or the active flag.
        //
        // A coupon that no longer qualifies drops the discount rather than failing the order: losing
        // the sale over an expired code is worse for the restaurant than charging full price.
        Coupon coupon = cart.getCoupon();
        BigDecimal discount = cart.getDiscount();

        if (coupon != null) {

            CouponPricing.Result quote = CouponPricing.evaluate(
                    CouponTerms.of(coupon),
                    cart.getSubtotal(),
                    cart.getItems().stream()
                            .mapToInt(i -> i.getQuantity() == null ? 0 : i.getQuantity())
                            .sum(),
                    businessTime.now());

            boolean claimed = quote.applied()
                    && couponRepository.claimOneUse(coupon.getId(), businessTime.now()) == 1;

            if (claimed) {
                // Recomputed from the coupon's current terms, not trusted from the cart.
                discount = quote.discount();
            } else {
                coupon = null;
                discount = BigDecimal.ZERO;
            }
        }

        // Service charge and tax are worked out again here, on the FINAL discount. The coupon above may
        // have just been dropped (expired, limit reached), which changes what is taxed. The rates are
        // the ones the guest was shown (copied onto the cart), not whatever the settings say now.
        OrderCharges.Breakdown charges = OrderCharges.compute(
                cart.getSubtotal(),
                discount,
                cart.getServiceChargeRate(),
                cart.getTaxRate(),
                Boolean.TRUE.equals(cart.getPricesIncludeTax()));
        BigDecimal total = charges.total();

        Order order = Order.builder()
                .orderNumber(nextOrderNumber(restaurant))
                .restaurant(restaurant)
                .restaurantTable(restaurantTable)
                .tableSession(diner == null ? null : diner.getTableSession())
                .dinerSession(diner)
                .dinerName(diner == null ? null : diner.getDisplayName())
                .status(OrderStatus.PENDING)
                .subtotal(cart.getSubtotal())
                .tax(charges.tax())
                .serviceCharge(charges.serviceCharge())
                .taxIncluded(charges.taxIncluded())
                .taxName(cart.getTaxName())
                .taxRate(cart.getTaxRate())
                .serviceChargeRate(cart.getServiceChargeRate())
                .pricesIncludeTax(Boolean.TRUE.equals(cart.getPricesIncludeTax()))
                .discount(discount)
                .total(total)
                .coupon(coupon)
                .couponCode(coupon == null ? null : coupon.getCode())
                .publicToken(newPublicToken())
                .notes(notes)
                .items(new ArrayList<>())
                .build();

        for (CartItem cartItem : cart.getItems()) {

            OrderItem orderItem =
                    OrderItem.builder()
                            .order(order)
                            .menuItem(cartItem.getMenuItem())
                            .itemName(
                                    cartItem.getMenuItem().getName())
                            .quantity(cartItem.getQuantity())
                            .unitPrice(cartItem.getUnitPrice())
                            .lineTotal(cartItem.getLineTotal())
                            .build();

            // What the guest chose, copied as it was: the kitchen sees it, the bill explains the
            // price, and later menu edits cannot change either.
            for (CartItemOption chosen : cartItem.getOptions()) {
                OrderItemOption copy = new OrderItemOption();
                copy.setOrderItem(orderItem);
                copy.setGroupName(chosen.getGroupName());
                copy.setOptionName(chosen.getOptionName());
                copy.setPriceDelta(chosen.getPriceDelta());
                orderItem.getOptions().add(copy);
            }

            order.getItems().add(orderItem);
        }

        // Stock comes out here, once the order's items are known. A tracked dish without enough
        // left throws, which rolls back the order, the coupon claim and the order number.
        stockService.consumeForOrder(order);

        Order savedOrder =
                orderRepository.save(order);

        // The first line of the order's history: who placed it. A guest is named as they gave their name.
        orderHistory.placed(
                savedOrder,
                diner != null
                        ? diner.getDisplayName()
                        : currentUserService.getCurrentUser().getEmail());

        notificationService.createNotification(
                savedOrder.getRestaurant(),
                savedOrder,
                "Order Placed",
                "Order "
                        + savedOrder.getOrderNumber()
                        + (diner == null
                                ? " has been placed."
                                : " has been placed by " + diner.getDisplayName() + "."),
                NotificationType.ORDER_PLACED
        );

        cart.setStatus(CartStatus.CHECKED_OUT);
        cartRepository.save(cart);

        return orderMapper.toResponse(savedOrder);
    }

    @Override
    @Transactional(readOnly = true)
    public OrderResponse getOrder(Long orderId) {

        Restaurant restaurant =
                currentUserService.getCurrentRestaurant();

        Order order =
                orderRepository
                        .findByIdAndRestaurant(
                                orderId,
                                restaurant
                        )
                        .orElseThrow(() ->
                                new ResourceNotFoundException(
                                        "Order not found"));

        return orderMapper.toResponse(order);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<OrderResponse> getOrders(int page, int size) {

        Restaurant restaurant =
                currentUserService.getCurrentRestaurant();

        Pageable pageable = PageRequest.of(
                Math.max(0, page),
                Math.min(Math.max(1, size), MAX_PAGE_SIZE));

        Page<Order> orders =
                orderRepository.findByRestaurantOrderByIdDesc(restaurant, pageable);

        return pageOfOrders(orders);
    }

    /**
     * Turns a page of orders into responses with their items and payment state, in a fixed number of
     * queries regardless of page size.
     *
     * <p>Shared by both listings so the batching cannot be done correctly in one and forgotten in the
     * other - which is exactly what happened to the status listing when the main one was fixed.
     */
    private PageResponse<OrderResponse> pageOfOrders(Page<Order> orders) {

        // Items for this page only, in one query. Fetch-joining them in the paged query would make
        // Hibernate page in memory, which reads every row to return twenty.
        if (!orders.isEmpty()) {
            orderRepository.findWithItemsByIdIn(
                    orders.getContent().stream().map(Order::getId).toList());
        }

        // Payment state for the whole page in one batch, so the list costs a fixed number of
        // queries rather than one per row.
        Map<Long, PaymentBalance.Result> balances =
                paymentService.getBalances(orders.getContent());

        return PageResponseUtils.fromPage(orders, order -> {

            OrderResponse response = orderMapper.toResponse(order);
            PaymentBalance.Result balance = balances.get(order.getId());

            if (balance != null) {
                response.setPaymentStatus(balance.status());
                response.setAmountPaid(balance.settled());
                response.setBalanceDue(balance.outstanding());
            }
            return response;
        });
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<OrderResponse> getOrdersByStatus(
            String status, int page, int size) {

        Restaurant restaurant =
                currentUserService.getCurrentRestaurant();

        OrderStatus orderStatus;

        try {
            orderStatus =
                    OrderStatus.valueOf(
                            status.toUpperCase()
                    );
        } catch (IllegalArgumentException ex) {

            throw new BadRequestException(
                    "Invalid order status");
        }

        Pageable pageable = PageRequest.of(
                Math.max(0, page),
                Math.min(Math.max(1, size), MAX_PAGE_SIZE));

        Page<Order> orders = orderRepository
                .findByRestaurantAndStatusOrderByIdDesc(restaurant, orderStatus, pageable);

        return pageOfOrders(orders);
    }

    @Override
    @Transactional
    public OrderResponse updateOrderStatus(
            Long orderId,
            String status) {

        return updateOrderStatus(orderId, status, null, null);
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrderHistoryEntryResponse> getOrderHistory(Long orderId) {

        Order order = orderRepository
                .findByIdAndRestaurant(orderId, currentUserService.getCurrentRestaurant())
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

        return statusEvents.findByOrderIdOrderByCreatedAtAscIdAsc(order.getId()).stream()
                .map(e -> new OrderHistoryEntryResponse(
                        e.getFromStatus(),
                        e.getToStatus(),
                        e.getActorLabel(),
                        e.getReason(),
                        e.getReason() == null ? null : e.getReason().label(),
                        e.getNote(),
                        e.getCreatedAt()))
                .toList();
    }

    @Override
    @Transactional
    public OrderResponse updateOrderStatus(
            Long orderId,
            String status,
            String reason,
            String note) {

        Restaurant restaurant =
                currentUserService.getCurrentRestaurant();

        // Locked: two staff changing one order at once would otherwise deadlock (see the repository method).
        Order order =
                orderRepository
                        .findByIdAndRestaurantForUpdate(
                                orderId,
                                restaurant
                        )
                        .orElseThrow(() ->
                                new ResourceNotFoundException(
                                        "Order not found"));

        OrderStatus target;

        try {
            target = OrderStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException ex) {

            throw new BadRequestException(
                    "Invalid order status");
        }

        // The same table the kitchen uses. This endpoint writes to the same field and used to accept
        // any value, so SERVED -> PENDING and CANCELLED -> PREPARING both went through.
        if (!OrderStatusPolicy.isAllowed(order.getStatus(), target)) {
            throw new BadRequestException(
                    OrderStatusPolicy.refusalMessage(order.getStatus(), target));
        }

        // A reason belongs to a cancellation and nothing else. Checked before anything is changed, so a
        // refused request leaves the order, its stock and its payments exactly as they were. Sending the
        // same reason again with a retried cancel is fine: the second one changes nothing.
        CancellationRules.Cancellation cancellation = null;
        if (target == OrderStatus.CANCELLED) {
            cancellation = CancellationRules.validate(reason, note);
        } else if ((reason != null && !reason.isBlank()) || (note != null && !note.isBlank())) {
            throw new BadRequestException("A reason only applies when cancelling an order.");
        }

        boolean cancelling =
                target == OrderStatus.CANCELLED
                        && order.getStatus() != OrderStatus.CANCELLED;

        if (cancelling) {

            // Money already taken is not undone by a status change. Refusing here rather than
            // silently cancelling keeps the refund an explicit, recorded act - and stops net
            // collections counting a payment whose order no longer exists.
            if (paymentService.hasCapturedPayments(order)) {
                throw new BadRequestException(
                        "This order has already been paid. Refund the payment before cancelling it.");
            }

            // A diner's pending choice belongs to the order; without this it outlived the order and
            // sat in the reconciliation panel's awaiting-confirmation bucket for ever.
            paymentService.cancelPendingPaymentsForOrder(order);

            // The coupon use goes back, so a cancelled order does not eat into a limited run.
            if (order.getCoupon() != null) {
                couponRepository.releaseOneUse(order.getCoupon().getId(), businessTime.now());
            }

            // And the dishes go back to stock.
            //
            // NOTE: restore is still not symmetric with consume - an order placed before a dish was
            // tracked, then cancelled after, credits stock that was never taken (BUG-002). That needs
            // a column on order_items to record what was actually consumed, which is a schema change
            // and is therefore not part of this group.
            stockService.restoreForOrder(order);
        }

        OrderStatus before = order.getStatus();

        order.setStatus(target);

        if (cancelling) {
            order.setCancelReason(cancellation.reason());
            order.setCancelNote(cancellation.note());
            order.setCancelledAt(businessTime.now());
            order.setCancelledBy(currentUserService.getCurrentUser().getEmail());
        }

        Order saved = orderRepository.save(order);

        orderHistory.changed(
                saved,
                before,
                target,
                cancelling ? cancellation.reason() : null,
                cancelling ? cancellation.note() : null);

        if (cancelling) {
            // Tells every open dashboard (and plays the cancellation sound there).
            notificationService.createNotification(
                    saved.getRestaurant(),
                    saved,
                    "Order Cancelled",
                    "Order " + saved.getOrderNumber() + " was cancelled.",
                    NotificationType.ORDER_CANCELLED);
        }

        return orderMapper.toResponse(saved);
    }

    /**
     * The next short number for this restaurant: ORD-1, ORD-2, ORD-3...
     *
     * <p>Numbers used to be a date plus 12 random hex characters, which was unique but unusable:
     * a diner could not read one back to staff, nor staff call it across a kitchen. Each restaurant
     * counts separately, and the counter row is locked for the transaction so simultaneous orders
     * take consecutive numbers. If the order then fails, the transaction rolls back and the number
     * is reused rather than leaving a gap.
     */
    /**
     * 32 characters of Base64url from 24 random bytes (192 bits). The tracking endpoint is
     * unauthenticated, so this value is the only thing protecting one diner's order from another;
     * it must come from a CSPRNG, not from the order number or a timestamp.
     */
    private static String newPublicToken() {

        byte[] bytes = new byte[24];
        SECURE_RANDOM.nextBytes(bytes);

        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String nextOrderNumber(Restaurant restaurant) {

        // The row is guaranteed to exist before it is locked; see ensureExists for why that order matters.
        orderCounterRepository.ensureExists(restaurant.getId(), businessTime.now());

        RestaurantOrderCounter counter = orderCounterRepository
                .findForUpdate(restaurant.getId())
                .orElseThrow(() -> new IllegalStateException("Order counter missing for restaurant " + restaurant.getId()));

        long number = counter.getNextNumber();
        counter.setNextNumber(number + 1);
        orderCounterRepository.save(counter);

        return "ORD-" + number;
    }
}