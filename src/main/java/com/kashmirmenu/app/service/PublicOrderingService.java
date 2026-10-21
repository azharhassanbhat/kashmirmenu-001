package com.kashmirmenu.app.service;

import java.util.Set;
import com.kashmirmenu.app.repository.MenuOptionGroupRepository;
import com.kashmirmenu.app.dto.response.OptionGroupResponse;
import com.kashmirmenu.app.concurrency.LockRetry;
import com.kashmirmenu.app.dto.request.AddCartItemRequest;
import com.kashmirmenu.app.dto.request.CreateOrderRequest;
import com.kashmirmenu.app.dto.request.CreatePaymentRequest;
import com.kashmirmenu.app.dto.request.PublicCartItemRequest;
import com.kashmirmenu.app.dto.request.UpdateCartItemRequest;
import com.kashmirmenu.app.dto.response.CartResponse;
import com.kashmirmenu.app.dto.response.MenuItemResponse;
import com.kashmirmenu.app.dto.response.OrderResponse;
import com.kashmirmenu.app.dto.response.OrderTrackingResponse;
import com.kashmirmenu.app.dto.response.PaymentResponse;
import com.kashmirmenu.app.dto.response.PublicMenuCategoryResponse;
import com.kashmirmenu.app.dto.response.PublicReviewsResponse;
import com.kashmirmenu.app.dto.response.PublicTableResponse;
import com.kashmirmenu.app.entity.DinerSession;
import com.kashmirmenu.app.entity.MenuItem;
import com.kashmirmenu.app.entity.Restaurant;
import com.kashmirmenu.app.entity.RestaurantTable;
import com.kashmirmenu.app.enums.PaymentMethod;
import com.kashmirmenu.app.exception.ResourceNotFoundException;
import com.kashmirmenu.app.mapper.MenuItemMapper;
import com.kashmirmenu.app.repository.CategoryRepository;
import com.kashmirmenu.app.repository.MenuItemRepository;
import com.kashmirmenu.app.entity.RestaurantPaymentSetting;
import com.kashmirmenu.app.repository.RestaurantPaymentSettingRepository;
import com.kashmirmenu.app.repository.RestaurantTableRepository;
import com.kashmirmenu.app.service.reviews.GooglePlacesClient;
import com.kashmirmenu.app.service.landing.LandingService;
import com.kashmirmenu.app.service.reviews.GoogleReviewLinks;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Unauthenticated, table-scoped operations for the customer QR flow. The
 * table's unguessable QR identifier is the only credential, so every
 * operation resolves (and validates) the table first and never accepts a
 * restaurant or table id from the caller.
 */
@Service
@RequiredArgsConstructor
public class PublicOrderingService {

    private final RestaurantTableRepository tableRepository;
    private final CategoryRepository categoryRepository;
    private final MenuItemRepository menuItemRepository;
    private final RestaurantPaymentSettingRepository paymentSettingRepository;
    private final MenuItemMapper menuItemMapper;
    private final CartService cartService;
    private final OrderService orderService;
    private final OrderTrackingService orderTrackingService;
    private final PaymentService paymentService;
    private final TableSessionService tableSessionService;
    private final LockRetry retry;
    private final GooglePlacesClient googlePlacesClient;
    private final MenuOptionGroupRepository optionGroupRepository;
    private final DishPopularity popularity;
    private final LandingService landingService;

    @Transactional(readOnly = true)
    public PublicTableResponse getTable(String qr) {

        RestaurantTable table = requireTable(qr);

        RestaurantPaymentSetting setting =
                paymentSettingRepository.findByRestaurantId(table.getRestaurant().getId()).orElse(null);

        // UPI is offered only when it is on AND the owner has a UPI ID and an uploaded QR: never a broken
        // image, never a placeholder. The details come from THIS table's restaurant, resolved server-side.
        PublicTableResponse.UpiPayment upi = upiPayment(setting);

        List<PaymentMethod> methods = setting == null
                ? List.of()
                : Arrays.stream(PaymentMethod.values())
                        .filter(PaymentMethod::isDinerSelectable)
                        .filter(setting::isPaymentMethodEnabled)
                        .filter(m -> m != PaymentMethod.UPI || upi != null)
                        .toList();

        return new PublicTableResponse(
                table.getRestaurant().getId(),
                table.getRestaurant().getName(),
                table.getRestaurant().getLogo(),
                table.getRestaurant().getPrimaryColor(),
                table.getRestaurant().getSecondaryColor(),
                table.getTableName(),
                methods,
                // The owner's own review link wins; the Place-ID link only serves restaurants that never set one.
                preferConfigured(
                        landingService.configuredReviewUrl(table.getRestaurant().getId()),
                        GoogleReviewLinks.writeReviewUrl(table.getRestaurant().getGooglePlaceId())),
                GoogleReviewLinks.mapsUrl(
                        table.getRestaurant().getGooglePlaceId(), table.getRestaurant().getName()),
                upi);
    }

    /** The restaurant's UPI payment details, or null when UPI cannot be offered. Shared with settlements. */
    public static PublicTableResponse.UpiPayment upiPayment(RestaurantPaymentSetting setting) {
        if (setting == null || !setting.isUpiEnabled()
                || setting.getUpiQrUrl() == null || setting.getUpiQrUrl().isBlank()
                || setting.getUpiId() == null || setting.getUpiId().isBlank()) {
            return null;
        }
        String name = setting.getMerchantName() == null || setting.getMerchantName().isBlank()
                ? null : setting.getMerchantName().strip();
        return new PublicTableResponse.UpiPayment(setting.getUpiQrUrl(), setting.getUpiId().strip(), name);
    }

    @Transactional(readOnly = true)
    public List<PublicMenuCategoryResponse> getMenu(String qr) {

        Long restaurantId = requireTable(qr).getRestaurant().getId();

        List<MenuItem> items = menuItemRepository.findByRestaurantIdAndActiveTrue(restaurantId);

        // Every dish's option groups in ONE query, so the menu does not cost a query per dish.
        Map<Long, List<OptionGroupResponse>> optionsByItem = items.isEmpty()
                ? Map.of()
                : optionGroupRepository
                        .findWithOptionsByMenuItemIds(items.stream().map(MenuItem::getId).toList())
                        .stream()
                        .collect(Collectors.groupingBy(
                                g -> g.getMenuItem().getId(),
                                Collectors.mapping(MenuOptionService::toResponse, Collectors.toList())));

        Set<Long> popular = popularity.popularIds(restaurantId);

        Map<Long, List<MenuItemResponse>> itemsByCategory =
                items.stream()
                        .sorted(Comparator
                                .comparing((MenuItem i) ->
                                        i.getDisplayOrder() == null ? 0 : i.getDisplayOrder())
                                .thenComparing(MenuItem::getName))
                        .collect(Collectors.groupingBy(
                                i -> i.getCategory().getId(),
                                Collectors.mapping(
                                        i -> {
                                            MenuItemResponse response = menuItemMapper.toResponse(i);
                                            response.setPopular(popular.contains(i.getId()));
                                            response.setOptionGroups(
                                                    optionsByItem.getOrDefault(i.getId(), List.of()));
                                            return response;
                                        },
                                        Collectors.toList())));

        return categoryRepository
                .findByRestaurantIdAndActiveTrueOrderByDisplayOrderAscNameAsc(restaurantId)
                .stream()
                .filter(c -> itemsByCategory.containsKey(c.getId()))
                .map(c -> new PublicMenuCategoryResponse(
                        c.getId(),
                        c.getName(),
                        c.getDescription(),
                        itemsByCategory.get(c.getId())))
                .toList();
    }

    /**
     * Google rating and reviews for the table's restaurant; link-only when the API is off.
     * Deliberately not transactional: the table query fetch-joins the restaurant, and the
     * Google call must not hold a database connection while it waits.
     */
    public PublicReviewsResponse getReviews(String qr) {

        Restaurant restaurant = requireTable(qr).getRestaurant();
        String placeId = restaurant.getGooglePlaceId();
        String writeUrl = preferConfigured(
                landingService.configuredReviewUrl(restaurant.getId()),
                GoogleReviewLinks.writeReviewUrl(placeId));
        String mapsUrl = GoogleReviewLinks.mapsUrl(placeId, restaurant.getName());

        if (writeUrl == null) {
            return PublicReviewsResponse.linkOnly(null, null);
        }
        if (placeId == null || placeId.isBlank()) {
            // A configured review link and no Place ID: the link works on its own; there is no rating to fetch.
            return PublicReviewsResponse.linkOnly(writeUrl, null);
        }
        PublicReviewsResponse details = googlePlacesClient.fetch(placeId, writeUrl);
        if (details == null) {
            return PublicReviewsResponse.linkOnly(writeUrl, mapsUrl);
        }
        // Prefer Google's own place URL; fall back to ours if the response lacked one.
        return details.mapsUrl() != null ? details : new PublicReviewsResponse(
                writeUrl, mapsUrl, details.rating(), details.ratingCount(), details.reviews());
    }

    // Every cart and order call below is made AS a guest. The guest comes from the token the client
    // sends, never from the request body, and a guest can only ever reach their own cart.
    //
    // Each one runs through LockRetry, from outside the service's own transaction: if two requests
    // collide on a row and the database aborts one, the abort rolls it back completely, so running it
    // again is safe and nearly always succeeds.

    public CartResponse getCart(String qr, String dinerToken) {
        requireTable(qr);
        DinerSession diner = requireDiner(qr, dinerToken);
        return retry.call("cart", () -> cartService.getCart(qr, diner));
    }

    public CartResponse addCartItem(String qr, String dinerToken, PublicCartItemRequest request) {
        requireTable(qr);
        DinerSession diner = requireDiner(qr, dinerToken);

        AddCartItemRequest add = new AddCartItemRequest();
        add.setTableQrIdentifier(qr);
        add.setMenuItemId(request.menuItemId());
        add.setQuantity(request.quantity());
        add.setOptionIds(request.optionIds());

        return retry.call("cart", () -> cartService.addItem(add, diner));
    }

    public CartResponse updateCartItem(String qr, String dinerToken, Long cartItemId, int quantity) {
        requireTable(qr);
        DinerSession diner = requireDiner(qr, dinerToken);

        UpdateCartItemRequest update = new UpdateCartItemRequest();
        update.setTableQrIdentifier(qr);
        update.setQuantity(quantity);

        return retry.call("cart", () -> cartService.updateItem(cartItemId, update, diner));
    }

    public void removeCartItem(String qr, String dinerToken, Long cartItemId) {
        requireTable(qr);
        DinerSession diner = requireDiner(qr, dinerToken);
        retry.run("cart", () -> cartService.removeItem(cartItemId, qr, diner));
    }

    public void clearCart(String qr, String dinerToken) {
        requireTable(qr);
        DinerSession diner = requireDiner(qr, dinerToken);
        retry.run("cart", () -> cartService.clearCart(qr, diner));
    }

    public CartResponse applyCoupon(String qr, String dinerToken, String couponCode) {
        requireTable(qr);
        DinerSession diner = requireDiner(qr, dinerToken);
        return retry.call("cart", () -> cartService.applyCoupon(qr, couponCode, diner));
    }

    public CartResponse removeCoupon(String qr, String dinerToken) {
        requireTable(qr);
        DinerSession diner = requireDiner(qr, dinerToken);
        return retry.call("cart", () -> cartService.removeCoupon(qr, diner));
    }

    public OrderResponse placeOrder(String qr, String dinerToken, String notes) {
        requireTable(qr);
        DinerSession diner = requireDiner(qr, dinerToken);
        return retry.call("order", () -> orderService.placeOrderForDiner(diner, qr, notes));
    }

    public OrderTrackingResponse trackOrder(String publicToken) {
        return orderTrackingService.trackOrderByToken(publicToken);
    }

    public PaymentResponse createPayment(
            String publicToken,
            CreatePaymentRequest request) {

        return paymentService.createPaymentByPublicToken(publicToken, request);
    }

    private DinerSession requireDiner(String qr, String dinerToken) {
        return tableSessionService.resolve(qr, dinerToken);
    }

    private static String preferConfigured(String configured, String fromPlaceId) {
        return configured != null ? configured : fromPlaceId;
    }

    private RestaurantTable requireTable(String qr) {

        return tableRepository.findWithRestaurantByQrIdentifier(qr)
                .filter(t -> Boolean.TRUE.equals(t.getActive()))
                .filter(t -> Boolean.TRUE.equals(t.getRestaurant().getActive()))
                .orElseThrow(() ->
                        new ResourceNotFoundException("Table not found"));
    }
}
