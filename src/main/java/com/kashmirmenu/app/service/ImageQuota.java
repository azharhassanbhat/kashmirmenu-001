package com.kashmirmenu.app.service;

import com.kashmirmenu.app.exception.BadRequestException;

/**
 * How much photo storage one restaurant may use. Photos live in the database, so without a limit one
 * restaurant (or one script) could grow it without bound. Pure, so the rule is testable.
 */
public final class ImageQuota {

    public static final long MAX_BYTES = 150L * 1024 * 1024;
    public static final long MAX_COUNT = 1000;

    private ImageQuota() {
    }

    /** Refuses (400) a new photo that would take the restaurant over either limit. Reaching a limit exactly is allowed. */
    public static void check(long usedBytes, long usedCount, long newBytes) {
        if (usedCount + 1 > MAX_COUNT) {
            throw new BadRequestException("You have reached the limit of " + MAX_COUNT
                    + " photos. Remove the photo from a dish you no longer sell, and unused photos are cleared automatically.");
        }
        if (usedBytes + newBytes > MAX_BYTES) {
            throw new BadRequestException("Your photo storage is full (" + (MAX_BYTES / (1024 * 1024))
                    + " MB). Photos no dish uses any more are cleared automatically after a day.");
        }
    }
}
