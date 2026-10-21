package com.kashmirmenu.app.service;

import com.kashmirmenu.app.config.TableSessionProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs the idle-table check on a timer. The first run waits a full interval, so start-up is never delayed. */
@Component
@RequiredArgsConstructor
public class TableSessionExpiryJob {

    private final TableSessionExpiryService expiryService;
    private final TableSessionProperties properties;

    @Scheduled(
            fixedDelayString = "${app.table-session.expiry-check-interval:PT10M}",
            initialDelayString = "${app.table-session.expiry-check-interval:PT10M}")
    public void run() {
        if (properties.isExpiryEnabled()) {
            expiryService.closeIdleVisits();
        }
    }
}
