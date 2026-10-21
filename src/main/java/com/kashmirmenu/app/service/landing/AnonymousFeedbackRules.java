package com.kashmirmenu.app.service.landing;

import com.kashmirmenu.app.exception.BadRequestException;

import java.util.Locale;

/**
 * What a guest may send through the anonymous feedback form. Pure.
 *
 * <p>Anonymous means nothing identifying is collected by default: no name, no phone, no device, no sign-in. A
 * contact detail is kept only when the guest explicitly asks to be contacted, and is then dropped from the stored
 * row unless that choice was made.
 */
public final class AnonymousFeedbackRules {

    public static final int MIN_RATING = 1;
    public static final int MAX_RATING = 5;
    public static final int MAX_COMMENT = 1000;
    public static final int MAX_CONTACT = 100;

    public enum FeedbackCategory {
        FOOD("Food"), SERVICE("Service"), CLEANLINESS("Cleanliness"), AMBIENCE("Ambience"), OTHER("Other");

        private final String label;

        FeedbackCategory(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public record Clean(int rating, FeedbackCategory category, String comment, String contact) {
    }

    private AnonymousFeedbackRules() {
    }

    /**
     * @param wantsReply true only when the guest ticked "you may contact me"; otherwise contact is discarded
     */
    public static Clean validate(Integer rating, String rawCategory, String rawComment,
                                 boolean wantsReply, String rawContact) {
        if (rating == null || rating < MIN_RATING || rating > MAX_RATING) {
            throw new BadRequestException("Please choose a rating from " + MIN_RATING + " to " + MAX_RATING + ".");
        }
        FeedbackCategory category = category(rawCategory);
        String comment = collapse(rawComment);
        if (comment.codePointCount(0, comment.length()) > MAX_COMMENT) {
            throw new BadRequestException("Please keep your comment under " + MAX_COMMENT + " characters.");
        }
        // A bare star rating is fine, but a low rating with no words and no category choice would tell the
        // restaurant nothing; the category is always present, so the rating alone is acceptable.
        String contact = null;
        if (wantsReply) {
            contact = collapse(rawContact);
            if (contact.isEmpty()) {
                throw new BadRequestException("Enter how the restaurant can reach you, or untick the box.");
            }
            if (contact.codePointCount(0, contact.length()) > MAX_CONTACT) {
                throw new BadRequestException("Please keep your contact details under " + MAX_CONTACT + " characters.");
            }
        }
        return new Clean(rating, category, comment.isEmpty() ? null : comment, contact);
    }

    /** A missing category means OTHER. An unknown one is refused rather than silently changed. */
    public static FeedbackCategory category(String raw) {
        if (raw == null || raw.isBlank()) {
            return FeedbackCategory.OTHER;
        }
        try {
            return FeedbackCategory.valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Please choose food, service, cleanliness, ambience or other.");
        }
    }

    private static String collapse(String raw) {
        return raw == null ? "" : raw.replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").strip();
    }
}
