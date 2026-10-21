package com.kashmirmenu.app.service.impl;

import com.kashmirmenu.app.audit.AuditAction;
import com.kashmirmenu.app.audit.AuditService;
import com.kashmirmenu.app.dto.request.CreateRestaurantPaymentSettingRequest;
import com.kashmirmenu.app.dto.request.UpdateRestaurantPaymentSettingRequest;
import com.kashmirmenu.app.dto.response.RestaurantPaymentSettingResponse;
import com.kashmirmenu.app.entity.Restaurant;
import com.kashmirmenu.app.entity.RestaurantPaymentSetting;
import com.kashmirmenu.app.mapper.RestaurantPaymentSettingMapper;
import com.kashmirmenu.app.repository.RestaurantPaymentSettingRepository;
import com.kashmirmenu.app.repository.RestaurantRepository;
import com.kashmirmenu.app.repository.StoredImageRepository;
import com.kashmirmenu.app.service.ImageKeys;
import com.kashmirmenu.app.security.service.CurrentUserService;
import com.kashmirmenu.app.service.RestaurantPaymentSettingService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import com.kashmirmenu.app.util.AfterCommit;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class RestaurantPaymentSettingServiceImpl
        implements RestaurantPaymentSettingService {

    private final RestaurantPaymentSettingRepository paymentSettingRepository;
    private final RestaurantRepository restaurantRepository;
    private final RestaurantPaymentSettingMapper mapper;
    private final CurrentUserService currentUserService;
    private final StoredImageRepository storedImages;
    private final AuditService auditService;

    @Override
    public RestaurantPaymentSettingResponse createPaymentSetting(
            Long restaurantId,
            CreateRestaurantPaymentSettingRequest request) {

        assertOwnRestaurant(restaurantId);

        Restaurant restaurant = getRestaurant(restaurantId);

        if (paymentSettingRepository.existsByRestaurant(restaurant)) {
            throw new IllegalStateException(
                    "Payment settings already exist for this restaurant.");
        }

        validateUpi(request.isUpiEnabled(), request.getUpiId());
        request.setUpiQrUrl(validatedQr(restaurantId, request.getUpiQrUrl()));

        RestaurantPaymentSetting paymentSetting = mapper.toEntity(request);
        paymentSetting.setRestaurant(restaurant);

        paymentSetting =
                paymentSettingRepository.save(paymentSetting);

        auditSettings(restaurantId, "Created. " + describe(paymentSetting));

        return mapper.toResponse(paymentSetting);
    }

    @Override
    @Transactional(readOnly = true)
    public RestaurantPaymentSettingResponse getPaymentSetting(
            Long restaurantId) {

        assertOwnRestaurant(restaurantId);

        RestaurantPaymentSetting paymentSetting =
                paymentSettingRepository.findByRestaurantId(restaurantId)
                        .orElseThrow(() -> new EntityNotFoundException(
                                "Payment settings not found."));

        return mapper.toResponse(paymentSetting);
    }

    @Override
    public RestaurantPaymentSettingResponse updatePaymentSetting(
            Long restaurantId,
            UpdateRestaurantPaymentSettingRequest request) {

        assertOwnRestaurant(restaurantId);

        RestaurantPaymentSetting paymentSetting =
                paymentSettingRepository.findByRestaurantId(restaurantId)
                        .orElseThrow(() -> new EntityNotFoundException(
                                "Payment settings not found."));

        validateUpi(request.isUpiEnabled(), request.getUpiId());
        // Re-saving the QR already stored is always fine; a different one must be this restaurant's own photo.
        String qr = request.getUpiQrUrl() == null || request.getUpiQrUrl().isBlank()
                ? null : request.getUpiQrUrl().strip();
        if (qr != null && !qr.equals(paymentSetting.getUpiQrUrl())) {
            qr = validatedQr(restaurantId, qr);
        }
        request.setUpiQrUrl(qr);

        String before = describe(paymentSetting);

        mapper.updateEntity(request, paymentSetting);

        paymentSetting =
                paymentSettingRepository.save(paymentSetting);

        // Where a restaurant's customers are told to send money: every change is on the permanent record.
        auditSettings(restaurantId, "Changed from [" + before + "] to [" + describe(paymentSetting) + "]");

        return mapper.toResponse(paymentSetting);
    }

    /**
     * The restaurant id arrives in the URL, so it is attacker-controlled and must be checked against
     * the signed-in user. Without this, any authenticated account could read or rewrite another
     * restaurant's payment settings - including disabling every payment method its diners can choose.
     *
     * <p>A mismatch answers exactly as a missing restaurant does, so ids cannot be probed.
     */
    private void assertOwnRestaurant(Long restaurantId) {

        Restaurant current = currentUserService.getCurrentRestaurant();

        if (current == null || !current.getId().equals(restaurantId)) {
            throw new EntityNotFoundException("Restaurant not found.");
        }
    }

    private Restaurant getRestaurant(Long restaurantId) {

        return restaurantRepository.findById(restaurantId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Restaurant not found."));
    }

    /**
     * A UPI ID (virtual payment address) is {@code name@handle}. Anything else, in particular characters
     * such as {@code & ? = /} or spaces, has no place in an address that ends up in a payment link or on a
     * QR code, so it is refused rather than stored.
     */
    private static final java.util.regex.Pattern UPI_ID =
            java.util.regex.Pattern.compile("^[A-Za-z0-9._-]{2,64}@[A-Za-z][A-Za-z0-9]{1,30}$");

    private void validateUpi(boolean upiEnabled, String upiId) {

        boolean blank = upiId == null || upiId.isBlank();

        if (upiEnabled && blank) {

            throw new IllegalArgumentException(
                    "UPI ID is required when UPI is enabled.");
        }

        if (!blank && !UPI_ID.matcher(upiId.trim()).matches()) {

            throw new IllegalArgumentException(
                    "That does not look like a UPI ID. It should look like name@bank, with letters, numbers, dots, dashes or underscores only.");
        }
    }

    /**
     * The QR is where customers are told to send money, so it must be one of OUR stored photos AND one
     * this restaurant uploaded: otherwise an owner could point their checkout at another restaurant's
     * photo, or at an external image that can be swapped later. Blank means "no QR".
     */
    private String validatedQr(Long restaurantId, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String url = raw.strip();
        String key = ImageKeys.keyFrom(url);
        if (key == null || !storedImages.existsByPublicKeyAndRestaurantId(key, restaurantId)) {
            throw new IllegalArgumentException("Upload your UPI QR image first, then save.");
        }
        return url;
    }

    /** A one-line description for the audit trail. The UPI ID is masked: enough to tell two apart, not to reuse. */
    private static String describe(RestaurantPaymentSetting s) {
        return "cash=" + s.isCashEnabled() + ", upi=" + s.isUpiEnabled() + ", card=" + s.isCardEnabled()
                + ", wallet=" + s.isWalletEnabled() + ", upiId=" + maskUpi(s.getUpiId())
                + ", upiQr=" + (s.getUpiQrUrl() == null ? "none" : "set");
    }

    static String maskUpi(String upiId) {
        if (upiId == null || upiId.isBlank()) {
            return "none";
        }
        int at = upiId.indexOf('@');
        if (at < 1) {
            return "***";
        }
        String name = upiId.substring(0, at);
        return name.substring(0, Math.min(2, name.length())) + "***" + upiId.substring(at);
    }

    private void auditSettings(Long restaurantId, String detail) {
        AfterCommit.run(() -> auditService.record(
                AuditAction.PAYMENT_SETTINGS_CHANGED, "RESTAURANT", restaurantId, detail));
    }
}