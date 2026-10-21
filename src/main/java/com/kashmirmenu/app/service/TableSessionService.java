package com.kashmirmenu.app.service;

import com.kashmirmenu.app.config.BusinessTime;
import com.kashmirmenu.app.config.TableSessionProperties;
import com.kashmirmenu.app.entity.DinerSession;
import com.kashmirmenu.app.entity.RestaurantTable;
import com.kashmirmenu.app.entity.TableSession;
import com.kashmirmenu.app.enums.TableSessionStatus;
import com.kashmirmenu.app.exception.BadRequestException;
import com.kashmirmenu.app.exception.ResourceNotFoundException;
import com.kashmirmenu.app.repository.DinerSessionRepository;
import com.kashmirmenu.app.repository.RestaurantTableRepository;
import com.kashmirmenu.app.repository.TableSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Table visits and the guests in them. Phase 1: joining and identifying a guest. Nothing here is
 * used by carts, orders or payments yet.
 */
@Service
@RequiredArgsConstructor
public class TableSessionService {

    /** Recording "last seen" on every request would turn each read into a write. */
    private static final Duration TOUCH_INTERVAL = Duration.ofMinutes(1);

    /** Shown for every way a guest session can be unusable; deliberately says nothing more. */
    public static final String SESSION_GONE =
            "Your table session has ended. Please scan the QR code and enter your name again.";

    private final RestaurantTableRepository tableRepository;
    private final TableSessionRepository tableSessionRepository;
    private final DinerSessionRepository dinerSessionRepository;
    private final TableSessionProperties properties;
    private final BusinessTime businessTime;

    /** A new guest, and the secret that is theirs alone. The secret exists only in this object. */
    public record Joined(DinerSession diner, String token) {
    }

    /**
     * Adds a guest to the table's open visit, opening one if the table has none.
     *
     * <p>Takes the table row's lock first, like every cart operation, so two guests scanning at
     * the same moment cannot both open a visit or be handed the same join number. That is also
     * the first lock in the documented order (restaurant_tables -> coupons -> order counter ->
     * inventory), so this cannot deadlock against checkout.
     */
    @Transactional
    public Joined join(String qr, String rawName) {

        String name = DinerIdentity.normalizeName(rawName);

        RestaurantTable table = tableRepository.findByQrIdentifierForUpdate(qr)
                .filter(t -> Boolean.TRUE.equals(t.getActive()))
                .filter(t -> Boolean.TRUE.equals(t.getRestaurant().getActive()))
                .orElseThrow(() -> new ResourceNotFoundException("Table not found"));

        LocalDateTime now = businessTime.now();

        TableSession visit = tableSessionRepository
                .findByRestaurantTableAndStatus(table, TableSessionStatus.OPEN)
                .orElseGet(() -> openVisit(table, now));

        List<DinerSession> guests = dinerSessionRepository.findByTableSessionOrderByJoinNumberAsc(visit);

        if (guests.size() >= properties.getMaxDiners()) {
            throw new BadRequestException(
                    "This table has reached its limit of guests. Please ask a member of staff.");
        }

        DinerSession diner = new DinerSession();
        diner.setTableSession(visit);
        diner.setJoinNumber(guests.isEmpty() ? 1 : guests.get(guests.size() - 1).getJoinNumber() + 1);
        diner.setDisplayName(DinerIdentity.uniqueDisplayName(
                name, guests.stream().map(DinerSession::getDisplayName).toList()));
        diner.setLastSeenAt(now);

        String token = DinerIdentity.newToken();
        diner.setTokenHash(DinerIdentity.hash(token));

        diner = dinerSessionRepository.save(diner);

        return new Joined(diner, token);
    }

    /**
     * The guest a token belongs to, provided it is for this table and its visit is still open.
     * Every failure looks the same - unknown token, another table's token, a closed visit - so a
     * caller learns nothing about which tokens exist.
     */
    @Transactional
    public DinerSession resolve(String qr, String token) {

        if (token == null || token.isBlank()) {
            throw new ResourceNotFoundException(SESSION_GONE);
        }

        DinerSession diner = dinerSessionRepository
                .findOpenByTokenHash(DinerIdentity.hash(token.trim()))
                .orElseThrow(() -> new ResourceNotFoundException(SESSION_GONE));

        TableSession visit = diner.getTableSession();

        if (!visit.getRestaurantTable().getQrIdentifier().equals(qr)) {
            throw new ResourceNotFoundException(SESSION_GONE);
        }

        // Only the guest's own row is refreshed here, never the visit's. Inserting an order, cart or
        // settlement takes a shared lock on the visit row (its foreign key), and updating that row
        // afterwards in the same transaction needs an exclusive one: two guests doing it at once each
        // hold the shared lock and wait for the other, which MySQL breaks by aborting one of them.
        // A visit's live activity is the newest last_seen_at among its guests.
        LocalDateTime now = businessTime.now();
        if (Duration.between(diner.getLastSeenAt(), now).compareTo(TOUCH_INTERVAL) >= 0) {
            diner.setLastSeenAt(now);
        }

        return diner;
    }

    private TableSession openVisit(RestaurantTable table, LocalDateTime now) {
        TableSession visit = new TableSession();
        visit.setRestaurant(table.getRestaurant());
        visit.setRestaurantTable(table);
        visit.setStatus(TableSessionStatus.OPEN);
        visit.setOpenedAt(now);
        visit.setLastActivityAt(now);
        return tableSessionRepository.save(visit);
    }
}
