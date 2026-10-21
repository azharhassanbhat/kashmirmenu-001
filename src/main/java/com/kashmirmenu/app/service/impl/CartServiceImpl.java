package com.kashmirmenu.app.service.impl;

import java.util.Map;
import java.util.List;
import java.util.HashMap;
import com.kashmirmenu.app.service.OptionPricing;
import com.kashmirmenu.app.service.BillingSettingsService;
import com.kashmirmenu.app.service.MenuOptionService;
import com.kashmirmenu.app.service.OrderCharges;
import com.kashmirmenu.app.repository.MenuOptionGroupRepository;
import com.kashmirmenu.app.entity.MenuOptionGroup;
import com.kashmirmenu.app.entity.MenuOption;
import com.kashmirmenu.app.entity.CartItemOption;
import com.kashmirmenu.app.dto.request.AddCartItemRequest;
import com.kashmirmenu.app.dto.request.CreateCartRequest;
import com.kashmirmenu.app.dto.request.UpdateCartItemRequest;
import com.kashmirmenu.app.dto.response.CartResponse;
import com.kashmirmenu.app.entity.Cart;
import com.kashmirmenu.app.entity.CartItem;
import com.kashmirmenu.app.entity.Coupon;
import com.kashmirmenu.app.entity.DinerSession;
import com.kashmirmenu.app.entity.MenuItem;
import com.kashmirmenu.app.entity.RestaurantTable;
import com.kashmirmenu.app.entity.TableSession;
import com.kashmirmenu.app.enums.CartStatus;
import com.kashmirmenu.app.enums.TableSessionStatus;
import com.kashmirmenu.app.exception.BadRequestException;
import com.kashmirmenu.app.exception.ResourceNotFoundException;
import com.kashmirmenu.app.mapper.CartMapper;
import com.kashmirmenu.app.config.BusinessTime;
import com.kashmirmenu.app.analytics.MoneyMath;
import com.kashmirmenu.app.coupon.CouponCode;
import com.kashmirmenu.app.coupon.CouponPricing;
import com.kashmirmenu.app.coupon.CouponTerms;
import com.kashmirmenu.app.coupon.CouponState;
import com.kashmirmenu.app.repository.CartItemRepository;
import com.kashmirmenu.app.repository.CouponRepository;
import com.kashmirmenu.app.repository.DinerSessionRepository;
import com.kashmirmenu.app.repository.CartRepository;
import com.kashmirmenu.app.repository.MenuItemRepository;
import com.kashmirmenu.app.repository.RestaurantTableRepository;
import com.kashmirmenu.app.service.CartService;
import com.kashmirmenu.app.service.StockService;
import com.kashmirmenu.app.service.TableSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class CartServiceImpl implements CartService {

    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final RestaurantTableRepository restaurantTableRepository;
    private final DinerSessionRepository dinerSessionRepository;
    private final MenuItemRepository menuItemRepository;
    private final MenuOptionGroupRepository optionGroupRepository;
    private final CartMapper cartMapper;
    private final CouponRepository couponRepository;
    private final StockService stockService;
    private final BusinessTime businessTime;
    private final BillingSettingsService billingSettings;

    @Value("${app.currency:INR}")
    private String currency;

    @Override
    public CartResponse getOrCreateCart(
            CreateCartRequest request) {

        // Staff API: no guest, so this is the table's staff cart.
        RestaurantTable table =
                getRestaurantTable(
                        request.getTableQrIdentifier(),
                        null
                );

        Cart cart =
                getOrCreateActiveCart(table, null);

        return cartMapper.toResponse(cart);
    }

    /**
     * Deliberately NOT read-only, though it reads. A guest's first look at their cart creates it, and
     * every cart operation takes the owner's row lock first. A read-only transaction rejects both on
     * MySQL (its driver refuses data-modifying statements on a read-only connection), while H2 accepts
     * them, so a read-only annotation here passes every H2 test and fails for the first request every
     * guest makes after joining.
     */
    @Override
    public CartResponse getCart(
            String tableQrIdentifier,
            DinerSession diner) {

        RestaurantTable table =
                getRestaurantTable(tableQrIdentifier, diner);

        Cart cart =
                getOrCreateActiveCart(table, diner);

        return cartMapper.toResponse(cart);
    }

    @Override
    public CartResponse addItem(
            AddCartItemRequest request,
            DinerSession diner) {

        RestaurantTable table =
                getRestaurantTable(request.getTableQrIdentifier(), diner);

        Cart cart =
                getOrCreateActiveCart(table, diner);

        MenuItem menuItem =
                menuItemRepository
                        .findByIdAndRestaurant(
                                request.getMenuItemId(),
                                table.getRestaurant()
                        )
                        .orElseThrow(() ->
                                new ResourceNotFoundException(
                                        "Menu item not found for this restaurant"
                                ));

        if (!Boolean.TRUE.equals(menuItem.getActive())
                || !Boolean.TRUE.equals(menuItem.getAvailable())) {

            throw new BadRequestException(
                    "Menu item is not available"
            );
        }

        // The guest sends only option ids. Every name and price change comes from the server's own
        // copy of the dish's groups, and the rules (required, single/multi, maximum, available) are
        // enforced here, so a client cannot choose what does not exist or set its own price.
        List<MenuOptionGroup> storedGroups =
                optionGroupRepository.findWithOptionsByMenuItemId(menuItem.getId());

        OptionPricing.Priced priced = OptionPricing.price(
                menuItem.getPrice(),
                MenuOptionService.toRules(storedGroups),
                request.getOptionIds());

        // A line is the dish plus its exact options: the same choices merge, different ones stay apart.
        CartItem cartItem =
                getOrCreateCartItem(
                        cart,
                        menuItem,
                        priced.key()
                );

        int wanted = cartItem.getQuantity() + request.getQuantity();

        // Checked here as well as at checkout, so a diner is told now rather than after writing
        // their notes and tapping Place order. The binding check is the one at placement. Counts the
        // dish across ALL its lines: one biryani with raita and one without draw on the same stock.
        stockService.assertAvailable(menuItem.getId(), wanted + quantityOnOtherLines(cart, cartItem));

        cartItem.setQuantity(wanted);

        cartItem.setUnitPrice(
                priced.unitPrice()
        );

        // Replaced on every add so the copied names and price changes always agree with the price.
        cartItem.getOptions().clear();
        Map<Long, MenuOption> storedOptions = new HashMap<>();
        for (MenuOptionGroup g : storedGroups) {
            for (MenuOption o : g.getOptions()) {
                storedOptions.put(o.getId(), o);
            }
        }
        for (OptionPricing.Chosen c : priced.chosen()) {
            CartItemOption copy = new CartItemOption();
            copy.setCartItem(cartItem);
            copy.setOption(storedOptions.get(c.optionId()));
            copy.setGroupName(c.groupName());
            copy.setOptionName(c.optionName());
            copy.setPriceDelta(c.priceDelta());
            cartItem.getOptions().add(copy);
        }

        cartItemRepository.save(cartItem);

        if (!cart.getItems().contains(cartItem)) {
            cart.getItems().add(cartItem);
        }

        recalculateTotals(cart);

        cartRepository.save(cart);

        return cartMapper.toResponse(cart);
    }

    @Override
    public CartResponse updateItem(
            Long cartItemId,
            UpdateCartItemRequest request,
            DinerSession diner) {

        RestaurantTable table =
                getRestaurantTable(request.getTableQrIdentifier(), diner);

        Cart cart =
                getOrCreateActiveCart(table, diner);

        CartItem cartItem =
                cartItemRepository
                        .findById(cartItemId)
                        .orElseThrow(() ->
                                new ResourceNotFoundException(
                                        "Cart item not found"
                                ));

        if (!cartItem.getCart()
                .getId()
                .equals(cart.getId())) {

            throw new ResourceNotFoundException(
                    "Cart item not found"
            );
        }

        stockService.assertAvailable(
                cartItem.getMenuItem().getId(),
                request.getQuantity() + quantityOnOtherLines(cart, cartItem));

        cartItem.setQuantity(
                request.getQuantity()
        );

        cartItemRepository.save(cartItem);

        recalculateTotals(cart);

        cartRepository.save(cart);

        return cartMapper.toResponse(cart);
    }

    @Override
    public void removeItem(
            Long cartItemId,
            String tableQrIdentifier,
            DinerSession diner) {

        RestaurantTable table =
                getRestaurantTable(tableQrIdentifier, diner);

        Cart cart =
                getOrCreateActiveCart(table, diner);

        CartItem cartItem =
                cartItemRepository
                        .findById(cartItemId)
                        .orElseThrow(() ->
                                new ResourceNotFoundException(
                                        "Cart item not found"
                                ));

        if (!cartItem.getCart()
                .getId()
                .equals(cart.getId())) {

            throw new ResourceNotFoundException(
                    "Cart item not found"
            );
        }

        cart.getItems().remove(cartItem);

        cartItemRepository.delete(cartItem);

        recalculateTotals(cart);

        cartRepository.save(cart);
    }

    @Override
    public void clearCart(
            String tableQrIdentifier,
            DinerSession diner) {

        RestaurantTable table =
                getRestaurantTable(tableQrIdentifier, diner);

        Cart cart =
                getOrCreateActiveCart(table, diner);

        cart.getItems().clear();

        recalculateTotals(cart);

        cartRepository.save(cart);
    }

    @Override
    public CartResponse applyCoupon(String tableQrIdentifier, String couponCode, DinerSession diner) {

        RestaurantTable table = getRestaurantTable(tableQrIdentifier, diner);
        Cart cart = getOrCreateActiveCart(table, diner);

        // Normalised to match how codes are stored (upper-cased, migration V29), so the lookup is a
        // plain equality that uses UNIQUE (restaurant_id, code).
        String code = CouponCode.normalise(couponCode);
        if (code.isEmpty()) {
            throw new BadRequestException("Enter a coupon code.");
        }

        // Scoped to this table's restaurant, so a code from one restaurant cannot be used at another.
        Coupon coupon = couponRepository
                .findByRestaurantAndNormalisedCode(table.getRestaurant(), code)
                .orElseThrow(() -> {
                    log.info("Coupon '{}' not found for restaurant {}", code, table.getRestaurant().getId());
                    return new BadRequestException(CouponState.NOT_FOUND.message());
                });

        CouponPricing.Result result = CouponPricing.evaluate(
                CouponTerms.of(coupon), cart.getSubtotal(), itemCount(cart), businessTime.now());

        if (!result.state().isValid()) {
            log.info("Coupon '{}' refused on cart {} (subtotal {}): {}",
                    code, cart.getId(), cart.getSubtotal(), result.state());
            String message = result.state().message();
            if (result.state() == CouponState.BELOW_MINIMUM) {
                message = "This coupon needs a minimum order of "
                        + MoneyMath.format(coupon.getMinimumOrderAmount(), currency)
                        + ". Add " + MoneyMath.format(result.shortfall(), currency) + " more to use it.";
            } else if (result.state() == CouponState.BELOW_MINIMUM_ITEMS) {
                int more = result.itemsShortfall();
                message = "This coupon needs at least " + coupon.getMinimumItems()
                        + " dishes. Add " + more + (more == 1 ? " more dish" : " more dishes") + " to use it.";
            }
            throw new BadRequestException(message);
        }

        log.info("Coupon '{}' applied to cart {}: discount {} on subtotal {}",
                code, cart.getId(), result.discount(), cart.getSubtotal());

        // Checking a code does not consume it: used_count moves only when an order is placed.
        cart.setCoupon(coupon);
        recalculateTotals(cart);
        cartRepository.save(cart);

        return cartMapper.toResponse(cart);
    }

    @Override
    public CartResponse removeCoupon(String tableQrIdentifier, DinerSession diner) {

        RestaurantTable table = getRestaurantTable(tableQrIdentifier, diner);
        Cart cart = getOrCreateActiveCart(table, diner);

        cart.setCoupon(null);
        recalculateTotals(cart);
        cartRepository.save(cart);

        return cartMapper.toResponse(cart);
    }

    /** Dishes in the cart, quantities summed: three of one dish counts as three. */
    private static int itemCount(Cart cart) {
        if (cart.getItems() == null) {
            return 0;
        }
        return cart.getItems().stream()
                .mapToInt(item -> item.getQuantity() == null ? 0 : item.getQuantity())
                .sum();
    }


    /**
     * Resolves whose cart this is and takes the lock that serialises work on it.
     *
     * <p>A guest (diner != null): locks THAT GUEST'S row. Two requests from one phone - a double
     * tap, or two first requests racing - queue behind each other, so they cannot each insert an
     * ACTIVE cart. Two different guests at the same table do not wait for each other: their carts
     * are independent, and that is the point of giving each guest one. The guest's visit and QR
     * code are checked again under the lock, so a table staff have just closed refuses new work.
     *
     * <p>Staff (diner == null): locks the table row, as every cart operation used to, because the
     * staff cart is still one shared cart per table.
     *
     * <p>Reads take the same write lock as writes. A read-only variant would be cheaper, but two
     * helpers that differ only in locking are easy to mix up, and the lock is held for milliseconds.
     * No path locks the table row while holding a guest's row, so the lock order documented on
     * RestaurantTableRepository (restaurant_tables, coupons, order counter, inventory) is intact.
     */
    private RestaurantTable getRestaurantTable(
            String qrIdentifier,
            DinerSession diner) {

        if (diner == null) {
            return restaurantTableRepository
                    .findByQrIdentifierForUpdate(qrIdentifier)
                    .orElseThrow(() ->
                            new ResourceNotFoundException(
                                    "Restaurant table not found"
                            ));
        }

        return lockDiner(qrIdentifier, diner).getTableSession().getRestaurantTable();
    }

    private DinerSession lockDiner(String qrIdentifier, DinerSession diner) {

        DinerSession locked = dinerSessionRepository
                .findByIdForUpdate(diner.getId())
                .orElseThrow(() -> new ResourceNotFoundException(TableSessionService.SESSION_GONE));

        TableSession visit = locked.getTableSession();

        if (visit.getStatus() != TableSessionStatus.OPEN
                || !visit.getRestaurantTable().getQrIdentifier().equals(qrIdentifier)) {
            throw new ResourceNotFoundException(TableSessionService.SESSION_GONE);
        }
        return locked;
    }

    /**
     * The guest's own ACTIVE cart, created on first use; or, for staff, the table's staff cart.
     * The caller already holds the lock from getRestaurantTable.
     */
    private Cart getOrCreateActiveCart(
            RestaurantTable table,
            DinerSession diner) {

        if (diner == null) {
            return cartRepository
                    .findByRestaurantTableAndStatusAndDinerSessionIsNull(table, CartStatus.ACTIVE)
                    .orElseGet(() -> cartRepository.save(
                            Cart.builder()
                                    .restaurant(table.getRestaurant())
                                    .restaurantTable(table)
                                    .status(CartStatus.ACTIVE)
                                    .build()));
        }

        // The same managed instance getRestaurantTable locked; the guest's row is already ours.
        DinerSession owner = dinerSessionRepository
                .findByIdForUpdate(diner.getId())
                .orElseThrow(() -> new ResourceNotFoundException(TableSessionService.SESSION_GONE));

        return cartRepository
                .findByDinerSessionAndStatus(owner, CartStatus.ACTIVE)
                .orElseGet(() -> cartRepository.save(
                        Cart.builder()
                                .restaurant(table.getRestaurant())
                                .restaurantTable(table)
                                .tableSession(owner.getTableSession())
                                .dinerSession(owner)
                                .status(CartStatus.ACTIVE)
                                .build()));
    }

    private CartItem getOrCreateCartItem(
            Cart cart,
            MenuItem menuItem,
            String optionsKey) {

        return cartItemRepository
                .findByCartAndMenuItemAndOptionsKey(
                        cart,
                        menuItem,
                        optionsKey
                )
                .orElseGet(() ->
                        CartItem.builder()
                                .cart(cart)
                                .menuItem(menuItem)
                                .quantity(0)
                                .unitPrice(menuItem.getPrice())
                                .lineTotal(BigDecimal.ZERO)
                                .optionsKey(optionsKey)
                                .build()
                );
    }

    /** Units of the same dish already in the cart on lines other than {@code line}. */
    private static int quantityOnOtherLines(Cart cart, CartItem line) {
        int total = 0;
        for (CartItem other : cart.getItems()) {
            if (other != line
                    && other.getMenuItem().getId().equals(line.getMenuItem().getId())
                    && other.getQuantity() != null) {
                total += other.getQuantity();
            }
        }
        return total;
    }

    private void recalculateTotals(
            Cart cart) {

        BigDecimal subtotal =
                BigDecimal.ZERO;

        for (CartItem item :
                cart.getItems()) {

            BigDecimal lineTotal =
                    item.getUnitPrice()
                            .multiply(
                                    BigDecimal.valueOf(
                                            item.getQuantity()
                                    )
                            );

            item.setLineTotal(lineTotal);

            subtotal =
                    subtotal.add(lineTotal);
        }

        cart.setSubtotal(subtotal);

        // The coupon is re-checked here rather than only when it is applied, because this runs on
        // every change to the cart. Removing an item below the coupon's minimum, or passing its
        // expiry mid-meal, drops it instead of quietly discounting the bill.
        BigDecimal discount = BigDecimal.ZERO;

        if (cart.getCoupon() != null) {
            CouponPricing.Result result = CouponPricing.evaluate(
                    CouponTerms.of(cart.getCoupon()), subtotal, itemCount(cart), businessTime.now());

            if (result.applied()) {
                discount = result.discount();
            } else {
                cart.setCoupon(null);
            }
        }

        cart.setDiscount(discount);

        // Service charge and tax come from the restaurant's own settings (zeros if it has none), are
        // worked out AFTER the coupon, and the rules used are copied onto the cart so what the guest
        // was shown is what placement will charge.
        BillingSettingsService.Rules rules = billingSettings.rulesFor(cart.getRestaurant().getId());
        OrderCharges.Breakdown charges = OrderCharges.compute(
                subtotal, discount, rules.serviceChargeRate(), rules.taxRate(), rules.pricesIncludeTax());

        cart.setServiceCharge(charges.serviceCharge());
        cart.setTax(charges.tax());
        cart.setTaxIncluded(charges.taxIncluded());
        cart.setTaxName(rules.taxName());
        cart.setTaxRate(rules.taxRate());
        cart.setServiceChargeRate(rules.serviceChargeRate());
        cart.setPricesIncludeTax(rules.pricesIncludeTax());

        cart.setTotal(charges.total());
    }
}