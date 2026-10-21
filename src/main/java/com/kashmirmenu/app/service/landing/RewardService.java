package com.kashmirmenu.app.service.landing;

import com.kashmirmenu.app.config.BusinessTime;
import com.kashmirmenu.app.dto.response.RewardClaimResponse;
import com.kashmirmenu.app.dto.response.RewardMemberResponse;
import com.kashmirmenu.app.dto.response.RewardRedeemResponse;
import com.kashmirmenu.app.dto.response.RewardsInfoResponse;
import com.kashmirmenu.app.entity.Order;
import com.kashmirmenu.app.entity.Payment;
import com.kashmirmenu.app.entity.Restaurant;
import com.kashmirmenu.app.entity.RestaurantLandingSettings;
import com.kashmirmenu.app.entity.RewardLedger;
import com.kashmirmenu.app.entity.RewardMember;
import com.kashmirmenu.app.enums.PaymentStatus;
import com.kashmirmenu.app.exception.BadRequestException;
import com.kashmirmenu.app.exception.ConflictException;
import com.kashmirmenu.app.exception.ResourceNotFoundException;
import com.kashmirmenu.app.repository.OrderRepository;
import com.kashmirmenu.app.repository.PaymentRepository;
import com.kashmirmenu.app.repository.RestaurantLandingSettingsRepository;
import com.kashmirmenu.app.repository.RestaurantTableRepository;
import com.kashmirmenu.app.repository.RewardLedgerRepository;
import com.kashmirmenu.app.repository.RewardMemberRepository;
import com.kashmirmenu.app.security.service.CurrentUserService;
import com.kashmirmenu.app.service.DinerIdentity;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * Optional rewards. A guest joins with nothing but a secret their own device made up; the server keeps only its
 * hash and hands back a short member code to read to staff. Points come from served-and-paid orders only,
 * worked out here from the order's own total, once per order (a database rule). Staff redeem a reward by
 * member code; the balance is spent with one conditional UPDATE so it can never go below zero.
 *
 * <p>Opening the landing page, tapping the review button, or anything else a client can do alone earns nothing.
 */
@Service
@RequiredArgsConstructor
public class RewardService {

    private static final String ALREADY_CLAIMED = "Points for this order have already been added.";

    private final RestaurantTableRepository tables;
    private final RestaurantLandingSettingsRepository settingsRepository;
    private final RewardMemberRepository members;
    private final RewardLedgerRepository ledger;
    private final OrderRepository orders;
    private final PaymentRepository payments;
    private final CurrentUserService currentUserService;
    private final BusinessTime businessTime;

    // ------------------------------------------------------------------ guests

    /** The rules, and the caller's balance when their secret matches a member. */
    @Transactional(readOnly = true)
    public RewardsInfoResponse info(String qr, String secret) {
        Restaurant restaurant = restaurantOf(qr);
        RestaurantLandingSettings s = enabledSettings(restaurant.getId());
        if (s == null) {
            return RewardsInfoResponse.off();
        }
        RewardMember member = RewardRules.plausibleSecret(secret)
                ? members.findByRestaurantIdAndSecretHash(restaurant.getId(), DinerIdentity.hash(secret)).orElse(null)
                : null;
        return build(s, member);
    }

    /** Joins (or, if this device already has, returns the existing membership). Never resets a balance. */
    @Transactional
    public RewardsInfoResponse join(String qr, String secret) {
        Restaurant restaurant = restaurantOf(qr);
        RestaurantLandingSettings s = enabledSettings(restaurant.getId());
        if (s == null) {
            throw new ResourceNotFoundException("Rewards are not available here.");
        }
        if (!RewardRules.plausibleSecret(secret)) {
            throw new BadRequestException("This device could not start a rewards membership. Please reload the page.");
        }
        String hash = DinerIdentity.hash(secret);
        RewardMember existing = members.findByRestaurantIdAndSecretHash(restaurant.getId(), hash).orElse(null);
        if (existing != null) {
            return build(s, existing);
        }
        String code;
        do {
            code = RewardRules.newMemberCode();
        } while (members.findByRestaurantIdAndMemberCode(restaurant.getId(), code).isPresent());

        RewardMember created = new RewardMember();
        created.setRestaurant(restaurant);
        created.setMemberCode(code);
        created.setSecretHash(hash);
        try {
            members.saveAndFlush(created);
        } catch (DataIntegrityViolationException e) {
            // A double tap raced itself; the database's one-membership-per-device rule stopped the second.
            throw new ConflictException("Your membership is being set up. Please try again in a moment.");
        }
        // A new member starts at zero.
        return new RewardsInfoResponse(true, s.getRewardsSpendPerPoint(), s.getRewardsRedeemPoints(),
                s.getRewardsRewardText(), s.getRewardsTerms(),
                new RewardsInfoResponse.Member(code, 0,
                        RewardRules.pointsToGo(0, s.getRewardsRedeemPoints()), false, List.of()));
    }

    /** Adds the points for one served-and-paid order of THIS restaurant, once. */
    @Transactional
    public RewardClaimResponse claim(String qr, String secret, String orderToken) {
        Restaurant restaurant = restaurantOf(qr);
        RestaurantLandingSettings s = enabledSettings(restaurant.getId());
        if (s == null) {
            throw new ResourceNotFoundException("Rewards are not available here.");
        }
        if (!RewardRules.plausibleSecret(secret)) {
            throw new BadRequestException("Join rewards first.");
        }
        RewardMember member = members.findByRestaurantIdAndSecretHash(restaurant.getId(), DinerIdentity.hash(secret))
                .orElseThrow(() -> new BadRequestException("Join rewards first."));

        // The token is the guest's own credential for the order. It must belong to this restaurant; both
        // "no such order" and "another restaurant's order" give the same bare answer.
        Order order = orders.findByPublicToken(orderToken == null ? "" : orderToken.strip())
                .filter(o -> o.getRestaurant().getId().equals(restaurant.getId()))
                .orElseThrow(() -> new ResourceNotFoundException("Order not found."));

        RewardRules.requireClaimable(order.getStatus());
        RewardRules.requirePaid(order.getTotal(), paidInFull(order.getId()));
        int points = RewardRules.pointsFor(order.getTotal(), s.getRewardsSpendPerPoint());
        if (points <= 0) {
            throw new BadRequestException("This order is too small to earn a point.");
        }
        if (ledger.existsByOrderId(order.getId())) {
            throw new ConflictException(ALREADY_CLAIMED);
        }

        RewardLedger row = new RewardLedger();
        row.setRestaurant(restaurant);
        row.setMember(member);
        row.setKind(RewardLedger.EARN);
        row.setPoints(points);
        row.setOrder(order);
        row.setNote("Order " + order.getOrderNumber());
        try {
            // Written before the balance moves: if two requests race, the database's one-claim-per-order rule
            // refuses the second here, and no points were added for it.
            ledger.saveAndFlush(row);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException(ALREADY_CLAIMED);
        }
        members.addPoints(member.getId(), points, businessTime.now());

        RewardMember fresh = members.findById(member.getId()).orElseThrow();
        return new RewardClaimResponse(points, build(s, fresh));
    }

    // ------------------------------------------------------------------ staff

    /** A member's balance, so staff can see whether a reward is due before redeeming. Own restaurant only. */
    @Transactional(readOnly = true)
    public RewardMemberResponse lookup(String rawCode) {
        Long restaurantId = currentUserService.getCurrentRestaurantId();
        RestaurantLandingSettings s = enabledSettings(restaurantId);
        if (s == null) {
            throw new BadRequestException("Rewards are switched off for this restaurant.");
        }
        String code = RewardRules.cleanCode(rawCode);
        if (code == null) {
            throw new BadRequestException("That is not a valid member code. It is 8 letters and numbers.");
        }
        RewardMember member = members.findByRestaurantIdAndMemberCode(restaurantId, code)
                .orElseThrow(() -> new ResourceNotFoundException("No member with that code."));
        int balance = member.getPointsBalance() == null ? 0 : member.getPointsBalance();
        int cost = s.getRewardsRedeemPoints();
        return new RewardMemberResponse(code, balance, cost, RewardRules.pointsToGo(balance, cost),
                RewardRules.canRedeem(balance, cost), s.getRewardsRewardText());
    }

    /** Redeems one reward for the member with this code, at the signed-in staff member's restaurant. */
    @Transactional
    public RewardRedeemResponse redeem(String rawCode) {
        Long restaurantId = currentUserService.getCurrentRestaurantId();
        RestaurantLandingSettings s = enabledSettings(restaurantId);
        if (s == null) {
            throw new BadRequestException("Rewards are switched off for this restaurant.");
        }
        String code = RewardRules.cleanCode(rawCode);
        if (code == null) {
            throw new BadRequestException("That is not a valid member code. It is 8 letters and numbers.");
        }
        RewardMember member = members.findByRestaurantIdAndMemberCode(restaurantId, code)
                .orElseThrow(() -> new ResourceNotFoundException("No member with that code."));

        int cost = s.getRewardsRedeemPoints();
        if (members.spendPoints(member.getId(), cost, businessTime.now()) == 0) {
            throw new BadRequestException("Not enough points yet for a reward.");
        }
        RewardLedger row = new RewardLedger();
        row.setRestaurant(currentUserService.getCurrentRestaurant());
        row.setMember(member);
        row.setKind(RewardLedger.REDEEM);
        row.setPoints(-cost);
        row.setNote("Redeemed by " + currentUserService.getCurrentUser().getEmail());
        ledger.save(row);

        RewardMember fresh = members.findById(member.getId()).orElseThrow();
        return new RewardRedeemResponse(code, cost, fresh.getPointsBalance(), s.getRewardsRewardText());
    }

    // ------------------------------------------------------------------ helpers

    private Restaurant restaurantOf(String qr) {
        return tables.findWithRestaurantByQrIdentifier(qr)
                .filter(t -> Boolean.TRUE.equals(t.getActive()))
                .filter(t -> Boolean.TRUE.equals(t.getRestaurant().getActive()))
                .orElseThrow(() -> new ResourceNotFoundException("Table not found"))
                .getRestaurant();
    }

    /** The restaurant's settings only when rewards are switched on; null otherwise. */
    private RestaurantLandingSettings enabledSettings(Long restaurantId) {
        return settingsRepository.findByRestaurantId(restaurantId)
                .filter(x -> Boolean.TRUE.equals(x.getRewardsEnabled()))
                .orElse(null);
    }

    /** Payments staff confirmed as received and that have not been refunded, summed. */
    private BigDecimal paidInFull(Long orderId) {
        BigDecimal sum = BigDecimal.ZERO;
        for (Payment p : payments.findByOrderIdOrderByIdAsc(orderId)) {
            if (p.getPaymentStatus() == PaymentStatus.SUCCESS && p.getAmount() != null) {
                sum = sum.add(p.getAmount());
            }
        }
        return sum;
    }

    private RewardsInfoResponse build(RestaurantLandingSettings s, RewardMember member) {
        if (member == null) {
            return new RewardsInfoResponse(true, s.getRewardsSpendPerPoint(), s.getRewardsRedeemPoints(),
                    s.getRewardsRewardText(), s.getRewardsTerms(), null);
        }
        int balance = member.getPointsBalance() == null ? 0 : member.getPointsBalance();
        int cost = s.getRewardsRedeemPoints();
        List<RewardsInfoResponse.Entry> history = ledger
                .findTop10ByMemberIdOrderByCreatedAtDescIdDesc(member.getId()).stream()
                .map(l -> new RewardsInfoResponse.Entry(l.getKind(), l.getPoints(), l.getNote(), l.getCreatedAt()))
                .toList();
        return new RewardsInfoResponse(true, s.getRewardsSpendPerPoint(), cost, s.getRewardsRewardText(),
                s.getRewardsTerms(),
                new RewardsInfoResponse.Member(member.getMemberCode(), balance,
                        RewardRules.pointsToGo(balance, cost), RewardRules.canRedeem(balance, cost), history));
    }
}
