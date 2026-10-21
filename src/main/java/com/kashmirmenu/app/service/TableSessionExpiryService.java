package com.kashmirmenu.app.service;

import com.kashmirmenu.app.config.BusinessTime;
import com.kashmirmenu.app.config.TableSessionProperties;
import com.kashmirmenu.app.repository.TableSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * Closes table visits that have gone quiet and owe nothing, so a table is not left "occupied" all day.
 *
 * <p>A visit is idle when no guest has acted for the configured time (three hours by default). Only an
 * idle visit with nothing owing is closed: money still owed, a payment waiting for staff, or an order the
 * kitchen has not confirmed all keep it open, and it shows on the owner's view as needing staff.
 *
 * <p>Each visit is its own transaction, so one failing cannot stop the rest, and the check is repeated
 * under the visit's lock. Running on several servers at once is safe for the same reason.
 *
 * <p>Deliberately not transactional itself: the loop must not hold one transaction open across visits.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TableSessionExpiryService {

    private final TableSessionAdminService adminService;
    private final TableSessionRepository tableSessionRepository;
    private final TableSessionProperties properties;
    private final BusinessTime businessTime;

    /** @return how many visits were closed */
    public int closeIdleVisits() {
        return closeIdleVisits(businessTime.now().minus(properties.getIdleTimeout()));
    }

    /** As above with an explicit cutoff: visits where no guest has acted since then. */
    public int closeIdleVisits(LocalDateTime cutoff) {
        int closed = 0;
        for (Long visitId : tableSessionRepository.findIdleIds(cutoff)) {
            try {
                if (adminService.closeIfIdleAndSettled(visitId, cutoff)) {
                    closed++;
                }
            } catch (RuntimeException e) {
                log.warn("Could not expire table session {}: {}", visitId, e.getMessage());
            }
        }
        if (closed > 0) {
            log.info("Closed {} idle table session(s) with nothing owing", closed);
        }
        return closed;
    }
}
