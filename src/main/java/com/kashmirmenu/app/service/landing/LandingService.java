package com.kashmirmenu.app.service.landing;

import com.kashmirmenu.app.common.response.PageResponse;
import com.kashmirmenu.app.config.BusinessTime;
import com.kashmirmenu.app.dto.request.AnonymousFeedbackRequest;
import com.kashmirmenu.app.dto.request.LandingSettingsRequest;
import com.kashmirmenu.app.dto.response.AdminLandingResponse;
import com.kashmirmenu.app.dto.response.AnonymousFeedbackAckResponse;
import com.kashmirmenu.app.dto.response.AnonymousFeedbackEntryResponse;
import com.kashmirmenu.app.dto.response.PublicLandingResponse;
import com.kashmirmenu.app.dto.response.WifiDetailsResponse;
import com.kashmirmenu.app.entity.AnonymousFeedback;
import com.kashmirmenu.app.entity.Order;
import com.kashmirmenu.app.entity.Restaurant;
import com.kashmirmenu.app.entity.RestaurantLandingSettings;
import com.kashmirmenu.app.entity.RestaurantTable;
import com.kashmirmenu.app.exception.BadRequestException;
import com.kashmirmenu.app.exception.ConflictException;
import com.kashmirmenu.app.exception.ResourceNotFoundException;
import com.kashmirmenu.app.repository.AnonymousFeedbackRepository;
import com.kashmirmenu.app.repository.OrderRepository;
import com.kashmirmenu.app.repository.RestaurantLandingSettingsRepository;
import com.kashmirmenu.app.repository.RestaurantTableRepository;
import com.kashmirmenu.app.security.service.CurrentUserService;
import com.kashmirmenu.app.service.FeedbackRules;
import com.kashmirmenu.app.service.ImageUrlRules;
import com.kashmirmenu.app.service.qr.QrCodeService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * The QR landing page: what a guest sees after scanning, and what the owner configures.
 *
 * <p><b>Who can see what.</b> Guests act through a table's QR identifier and only ever see that table's
 * restaurant. The owner acts through their own account: the restaurant is read from the signed-in user, never
 * from a request, so one restaurant cannot reach another's settings or feedback.
 *
 * <p>A restaurant with no saved settings has the landing page switched OFF, so the QR keeps opening the menu
 * exactly as before until an owner opts in.
 */
@Service
@RequiredArgsConstructor
public class LandingService {

    static final int MAX_PAGE_SIZE = 50;
    private static final Pattern NON_DIAL = Pattern.compile("[^0-9+]");

    private final RestaurantTableRepository tables;
    private final RestaurantLandingSettingsRepository settingsRepository;
    private final AnonymousFeedbackRepository feedbackRepository;
    private final OrderRepository orders;
    private final CurrentUserService currentUserService;
    private final QrCodeService qrCodeService;
    private final BusinessTime businessTime;
    private final com.kashmirmenu.app.service.NotificationService notificationService;

    // ================================================================== guests

    @Transactional(readOnly = true)
    public PublicLandingResponse landing(String qr) {
        RestaurantTable table = requireTable(qr);
        Restaurant restaurant = table.getRestaurant();
        RestaurantLandingSettings s = settingsRepository.findByRestaurantId(restaurant.getId()).orElse(null);
        if (s == null) {
            return new PublicLandingResponse(false, restaurant.getId(), restaurant.getName(), safeImage(restaurant.getLogo()),
                    restaurant.getPrimaryColor(), restaurant.getSecondaryColor(), table.getTableName(),
                    null, null, null, new PublicLandingResponse.Hours(false, false, "", List.of()),
                    emptyLinks(restaurant), null, null, List.of());
        }

        String reviewUrl = LandingLinks.isSafeGoogleReview(s.getGoogleReviewUrl()) ? s.getGoogleReviewUrl() : null;
        boolean wifiOn = Boolean.TRUE.equals(s.getWifiEnabled()) && notBlank(s.getWifiSsid());
        boolean rewardsOn = Boolean.TRUE.equals(s.getRewardsEnabled());

        List<String> shown = new ArrayList<>();
        for (String key : LandingActions.fromStored(s.getActionOrder())) {
            boolean available = switch (key) {
                case "REWARDS" -> rewardsOn;
                case "REVIEW" -> reviewUrl != null;
                case "MENU" -> true;
                case "WIFI" -> wifiOn;
                case "FEEDBACK" -> Boolean.TRUE.equals(s.getFeedbackEnabled());
                case "ASSISTANCE" -> Boolean.TRUE.equals(s.getAssistanceEnabled());
                default -> false;
            };
            if (available) {
                shown.add(key);
            }
        }

        OpeningHours hours = parseQuietly(s.getOpeningHours());
        OpeningHours.Status status = hours.status(businessTime.now());
        List<PublicLandingResponse.Day> week = hours.week().stream()
                .map(d -> new PublicLandingResponse.Day(d.day(), d.hours())).toList();

        String phone = restaurant.getPhone();
        PublicLandingResponse.Links links = new PublicLandingResponse.Links(
                reviewUrl,
                quiet(LandingLinks::instagram, s.getInstagramUrl()),
                quiet(LandingLinks::youtube, s.getYoutubeUrl()),
                quiet(LandingLinks::facebook, s.getFacebookUrl()),
                quiet(v -> LandingLinks.https(v, "website"), s.getWebsiteUrl()),
                directionsUrl(restaurant, s),
                notBlank(phone) ? phone.strip() : null,
                dial(phone));

        return new PublicLandingResponse(
                Boolean.TRUE.equals(s.getLandingEnabled()),
                restaurant.getId(), restaurant.getName(), safeImage(restaurant.getLogo()),
                restaurant.getPrimaryColor(), restaurant.getSecondaryColor(), table.getTableName(),
                blankToNull(s.getTagline()),
                safeImage(s.getCoverImageUrl()),
                blankToNull(s.getAnnouncement()),
                new PublicLandingResponse.Hours(status.known(), status.open(), status.label(), week),
                links,
                wifiOn ? new PublicLandingResponse.Wifi(s.getWifiSsid(), s.getWifiSecurity(),
                        notBlank(s.getWifiPassword())) : null,
                rewardsOn ? new PublicLandingResponse.Rewards(s.getRewardsSpendPerPoint(),
                        s.getRewardsRedeemPoints(), blankToNull(s.getRewardsRewardText()),
                        blankToNull(s.getRewardsTerms())) : null,
                shown);
    }

    /** The configured Google review link for a restaurant, or null. Used by the menu's own review button too. */
    @Transactional(readOnly = true)
    public String configuredReviewUrl(Long restaurantId) {
        return settingsRepository.findByRestaurantId(restaurantId)
                .map(RestaurantLandingSettings::getGoogleReviewUrl)
                .filter(LandingLinks::isSafeGoogleReview)
                .orElse(null);
    }

    /** Wi-Fi credentials for the table's restaurant. 404 when the restaurant offers no guest Wi-Fi. */
    @Transactional(readOnly = true)
    public WifiDetailsResponse wifi(String qr) {
        RestaurantLandingSettings s = requireWifi(qr);
        return new WifiDetailsResponse(s.getWifiSsid(), s.getWifiSecurity(),
                "NONE".equals(s.getWifiSecurity()) ? null : s.getWifiPassword());
    }

    /** The standard Wi-Fi join QR as a PNG, drawn by the app's own QR generator. */
    @Transactional(readOnly = true)
    public byte[] wifiQr(String qr) {
        RestaurantLandingSettings s = requireWifi(qr);
        String payload = WifiRules.qrPayload(
                s.getWifiSsid(), WifiRules.Security.parse(s.getWifiSecurity()), s.getWifiPassword());
        return qrCodeService.generateQrCode(payload, 480, 480);
    }

    /** Stores anonymous feedback for the table's restaurant. Nothing identifying is kept unless the guest opts in. */
    @Transactional
    public AnonymousFeedbackAckResponse submitFeedback(String qr, AnonymousFeedbackRequest request) {
        Restaurant restaurant = requireTable(qr).getRestaurant();
        RestaurantLandingSettings s = settingsRepository.findByRestaurantId(restaurant.getId()).orElse(null);
        if (s == null || !Boolean.TRUE.equals(s.getFeedbackEnabled())) {
            throw new ResourceNotFoundException("Feedback is not available here.");
        }
        AnonymousFeedbackRules.Clean clean = AnonymousFeedbackRules.validate(
                FeedbackRules.wholeRating(request.rating()),
                request.category(),
                request.comment(),
                Boolean.TRUE.equals(request.wantsReply()),
                request.contact());

        Order order = null;
        if (notBlank(request.orderToken())) {
            // The token is the guest's own credential for that order. It must belong to THIS restaurant; the
            // answer is the same bare message either way so it confirms nothing about other restaurants.
            order = orders.findByPublicToken(request.orderToken().strip())
                    .filter(o -> o.getRestaurant().getId().equals(restaurant.getId()))
                    .orElseThrow(() -> new BadRequestException("That order could not be found."));
        }

        AnonymousFeedback row = new AnonymousFeedback();
        row.setRestaurant(restaurant);
        row.setOrder(order);
        row.setRating(clean.rating());
        row.setCategory(clean.category().name());
        row.setComment(clean.comment());
        row.setContact(clean.contact());
        feedbackRepository.save(row);
        notificationService.createNotification(
                restaurant,
                order,
                "New customer feedback",
                "A guest left feedback: " + clean.rating() + " out of 5.",
                com.kashmirmenu.app.enums.NotificationType.FEEDBACK_RECEIVED);
        return new AnonymousFeedbackAckResponse(true, "Thank you. Your feedback has reached the restaurant.");
    }

    // ================================================================== the owner

    @Transactional(readOnly = true)
    public AdminLandingResponse get() {
        Long restaurantId = currentUserService.getCurrentRestaurantId();
        return toAdmin(settingsRepository.findByRestaurantId(restaurantId).orElse(null));
    }

    @Transactional
    public AdminLandingResponse save(LandingSettingsRequest r) {
        Restaurant restaurant = currentUserService.getCurrentRestaurant();
        Long restaurantId = currentUserService.getCurrentRestaurantId();
        RestaurantLandingSettings s = settingsRepository.findByRestaurantId(restaurantId).orElseGet(() -> {
            RestaurantLandingSettings created = new RestaurantLandingSettings();
            created.setRestaurant(restaurant);
            return created;
        });

        // Everything is checked before anything is applied, so a bad value leaves the saved settings untouched.
        String review = LandingLinks.googleReview(r.googleReviewUrl());
        String instagram = LandingLinks.instagram(r.instagramUrl());
        String youtube = LandingLinks.youtube(r.youtubeUrl());
        String facebook = LandingLinks.facebook(r.facebookUrl());
        String website = LandingLinks.https(r.websiteUrl(), "website link");
        String map = LandingLinks.https(r.mapUrl(), "map link");
        ImageUrlRules.requireValidIfChanged(r.coverImageUrl(), s.getCoverImageUrl());
        String cover = blankToNull(r.coverImageUrl());
        String hours = OpeningHours.parse(r.openingHours()).canonical();

        boolean wifiOn = Boolean.TRUE.equals(r.wifiEnabled());
        String ssid = s.getWifiSsid();
        String security = s.getWifiSecurity();
        String password = s.getWifiPassword();
        if (wifiOn) {
            // A null password keeps the saved one, so saving other changes never needs the password re-sent.
            String candidate = r.wifiPassword() != null ? r.wifiPassword() : s.getWifiPassword();
            WifiRules.Wifi wifi = WifiRules.validate(r.wifiSsid(), r.wifiSecurity(), candidate);
            ssid = wifi.ssid();
            security = wifi.security().name();
            password = wifi.password();
        }
        // Wi-Fi switched off: whatever is saved stays as it was, so switching it back on is one click and a
        // half-filled Wi-Fi section can never block saving the rest of the page.

        boolean rewardsOn = Boolean.TRUE.equals(r.rewardsEnabled());
        java.math.BigDecimal spend = RewardRules.normaliseSpend(r.rewardsSpendPerPoint() != null
                ? r.rewardsSpendPerPoint() : s.getRewardsSpendPerPoint());
        Integer redeem = r.rewardsRedeemPoints() != null ? r.rewardsRedeemPoints() : s.getRewardsRedeemPoints();
        RewardRules.validateSettings(spend, redeem);
        if (rewardsOn && !notBlank(r.rewardsRewardText())) {
            throw new BadRequestException("Say what the reward is (for example, a free kahwa).");
        }

        s.setLandingEnabled(r.landingEnabled() == null ? Boolean.TRUE.equals(s.getLandingEnabled()) : r.landingEnabled());
        s.setTagline(blankToNull(r.tagline()));
        s.setCoverImageUrl(cover);
        s.setAnnouncement(blankToNull(r.announcement()));
        s.setOpeningHours(hours.isEmpty() ? null : hours);
        s.setGoogleReviewUrl(review);
        s.setInstagramUrl(instagram);
        s.setYoutubeUrl(youtube);
        s.setFacebookUrl(facebook);
        s.setWebsiteUrl(website);
        s.setMapUrl(map);
        s.setWifiEnabled(wifiOn);
        s.setWifiSsid(ssid);
        s.setWifiSecurity(security == null ? "WPA" : security);
        s.setWifiPassword(password);
        s.setFeedbackEnabled(r.feedbackEnabled() == null || r.feedbackEnabled());
        s.setAssistanceEnabled(r.assistanceEnabled() == null || r.assistanceEnabled());
        s.setRewardsEnabled(rewardsOn);
        s.setRewardsSpendPerPoint(spend);
        s.setRewardsRedeemPoints(redeem);
        s.setRewardsRewardText(blankToNull(r.rewardsRewardText()));
        s.setRewardsTerms(blankToNull(r.rewardsTerms()));
        s.setActionOrder(r.actions() == null ? s.getActionOrder() : LandingActions.toStored(r.actions()));
        try {
            return toAdmin(settingsRepository.saveAndFlush(s));
        } catch (DataIntegrityViolationException e) {
            // Two first saves raced; the database's one-row-per-restaurant rule stopped the second.
            throw new ConflictException("Your settings are being saved. Please try again in a moment.");
        }
    }

    /** The signed-in restaurant's landing-page feedback, newest first. */
    @Transactional(readOnly = true)
    public PageResponse<AnonymousFeedbackEntryResponse> feedback(int page, int size) {
        Long restaurantId = currentUserService.getCurrentRestaurantId();
        PageRequest paging = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), MAX_PAGE_SIZE));
        Page<AnonymousFeedback> result =
                feedbackRepository.findByRestaurantIdOrderByCreatedAtDescIdDesc(restaurantId, paging);
        return PageResponse.<AnonymousFeedbackEntryResponse>builder()
                .items(result.getContent().stream().map(f -> new AnonymousFeedbackEntryResponse(
                        f.getId(), f.getRating(), f.getCategory(), f.getComment(), f.getContact(),
                        f.getOrder() == null ? null : f.getOrder().getOrderNumber(), f.getCreatedAt())).toList())
                .page(result.getNumber())
                .size(result.getSize())
                .totalItems(result.getTotalElements())
                .totalPages(result.getTotalPages())
                .hasNext(result.hasNext())
                .hasPrevious(result.hasPrevious())
                .build();
    }

    // ================================================================== helpers

    private RestaurantTable requireTable(String qr) {
        return tables.findWithRestaurantByQrIdentifier(qr)
                .filter(t -> Boolean.TRUE.equals(t.getActive()))
                .filter(t -> Boolean.TRUE.equals(t.getRestaurant().getActive()))
                .orElseThrow(() -> new ResourceNotFoundException("Table not found"));
    }

    private RestaurantLandingSettings requireWifi(String qr) {
        Long restaurantId = requireTable(qr).getRestaurant().getId();
        return settingsRepository.findByRestaurantId(restaurantId)
                .filter(s -> Boolean.TRUE.equals(s.getWifiEnabled()) && notBlank(s.getWifiSsid()))
                .orElseThrow(() -> new ResourceNotFoundException("Guest Wi-Fi is not available here."));
    }

    private static AdminLandingResponse toAdmin(RestaurantLandingSettings s) {
        if (s == null) {
            // Nothing saved yet: the defaults, with the landing page still off.
            return new AdminLandingResponse(false, null, null, null, null, null, null, null, null, null, null,
                    false, null, "WPA", false, true, true, false, RewardRules.DEFAULT_SPEND_PER_POINT,
                    RewardRules.DEFAULT_REDEEM_POINTS, null, null, LandingActions.ALL, LandingActions.ALL);
        }
        return new AdminLandingResponse(
                Boolean.TRUE.equals(s.getLandingEnabled()), s.getTagline(), s.getCoverImageUrl(),
                s.getAnnouncement(), s.getOpeningHours(), s.getGoogleReviewUrl(), s.getInstagramUrl(),
                s.getYoutubeUrl(), s.getFacebookUrl(), s.getWebsiteUrl(), s.getMapUrl(),
                Boolean.TRUE.equals(s.getWifiEnabled()), s.getWifiSsid(), s.getWifiSecurity(),
                notBlank(s.getWifiPassword()),
                Boolean.TRUE.equals(s.getFeedbackEnabled()), Boolean.TRUE.equals(s.getAssistanceEnabled()),
                Boolean.TRUE.equals(s.getRewardsEnabled()), s.getRewardsSpendPerPoint(),
                s.getRewardsRedeemPoints(), s.getRewardsRewardText(), s.getRewardsTerms(),
                LandingActions.fromStored(s.getActionOrder()), LandingActions.ALL);
    }

    private static PublicLandingResponse.Links emptyLinks(Restaurant r) {
        return new PublicLandingResponse.Links(null, null, null, null, null, null,
                notBlank(r.getPhone()) ? r.getPhone().strip() : null, dial(r.getPhone()));
    }

    /** The owner's map link, else Google's documented keyless search URL for the address. Never needs a Place ID. */
    private static String directionsUrl(Restaurant r, RestaurantLandingSettings s) {
        String map = quiet(v -> LandingLinks.https(v, "map link"), s.getMapUrl());
        if (map != null) {
            return map;
        }
        StringBuilder q = new StringBuilder();
        for (String part : new String[]{r.getName(), r.getAddress(), r.getCity(), r.getState()}) {
            if (notBlank(part)) {
                if (q.length() > 0) {
                    q.append(", ");
                }
                q.append(part.strip());
            }
        }
        if (!notBlank(r.getAddress())) {
            return null; // a name alone is too vague to give directions to
        }
        return "https://www.google.com/maps/search/?api=1&query=" + URLEncoder.encode(q.toString(), StandardCharsets.UTF_8);
    }

    /** Digits and a leading plus only, for a tel: link. Null when there is no usable number. */
    static String dial(String phone) {
        if (!notBlank(phone)) {
            return null;
        }
        String d = NON_DIAL.matcher(phone).replaceAll("");
        String digits = d.startsWith("+") ? d.substring(1).replace("+", "") : d.replace("+", "");
        if (digits.length() < 5 || digits.length() > 15) {
            return null;
        }
        return (d.startsWith("+") ? "+" : "") + digits;
    }

    private static OpeningHours parseQuietly(String stored) {
        try {
            return OpeningHours.parse(stored);
        } catch (BadRequestException e) {
            return OpeningHours.parse(null);
        }
    }

    /** Re-checks a stored link on the way out, so a bad value that got into the table can never reach a guest. */
    private static String quiet(UnaryOperator<String> check, String stored) {
        if (!notBlank(stored)) {
            return null;
        }
        try {
            return check.apply(stored);
        } catch (BadRequestException e) {
            return null;
        }
    }

    /**
     * A photo address fit to show: one of our own uploads (/api/public/images/{key}) or an https link, by the
     * same rule the dish photos use. Anything else is left out, so the screen falls back to its illustration.
     */
    static String safeImage(String stored) {
        return notBlank(stored) && ImageUrlRules.problem(stored) == null ? stored.strip() : null;
    }

    private static boolean notBlank(String v) {
        return v != null && !v.isBlank();
    }

    private static String blankToNull(String v) {
        return notBlank(v) ? v.strip() : null;
    }
}
