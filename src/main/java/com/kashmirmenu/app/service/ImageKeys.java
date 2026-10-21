package com.kashmirmenu.app.service;

import java.security.SecureRandom;
import java.util.regex.Pattern;

/**
 * The public address of an uploaded photo. The key is 128 random bits, so a photo's address cannot be
 * guessed or counted up to (unlike an id), which matters because the address is public: anyone holding
 * it can fetch the photo, and nobody can find photos they were not given.
 */
public final class ImageKeys {

    public static final String PATH_PREFIX = "/api/public/images/";
    private static final Pattern KEY = Pattern.compile("[0-9a-f]{32}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private ImageKeys() {
    }

    public static String newKey() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(32);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    public static boolean isValid(String key) {
        return key != null && KEY.matcher(key).matches();
    }

    public static String urlFor(String key) {
        return PATH_PREFIX + key;
    }

    /** The key inside one of OUR photo addresses, or null for anything else (an external link, blank, junk). */
    public static String keyFrom(String url) {
        if (url == null || !url.startsWith(PATH_PREFIX)) {
            return null;
        }
        String key = url.substring(PATH_PREFIX.length());
        return isValid(key) ? key : null;
    }
}
