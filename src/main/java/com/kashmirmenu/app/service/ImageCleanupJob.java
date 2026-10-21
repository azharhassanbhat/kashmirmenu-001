package com.kashmirmenu.app.service;

import com.kashmirmenu.app.config.BusinessTime;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Removes photos nothing uses any more: a photo that was replaced, or uploaded and never saved on a dish.
 * Only photos older than a day are touched, so one uploaded while a dish form is still open is never
 * deleted from under the person filling it in.
 */
@Component
@RequiredArgsConstructor
public class ImageCleanupJob {

    static final Duration GRACE = Duration.ofHours(24);

    private final ImageService imageService;
    private final BusinessTime businessTime;

    @Scheduled(
            fixedDelayString = "${app.images.cleanup-interval:PT6H}",
            initialDelayString = "${app.images.cleanup-initial-delay:PT1H}")
    public void run() {
        imageService.deleteUnreferencedOlderThan(businessTime.now().minus(GRACE));
    }
}
