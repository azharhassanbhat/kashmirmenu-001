package com.kashmirmenu.app.service.landing;

import com.kashmirmenu.app.exception.BadRequestException;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Guest Wi-Fi details and the standard Wi-Fi QR payload ({@code WIFI:T:WPA;S:name;P:password;;}) that iOS and
 * Android cameras offer to join directly. Pure, so the escaping is tested rather than assumed.
 */
public final class WifiRules {

    public static final int MAX_SSID_BYTES = 32;

    private WifiRules() {
    }

    /** Only the three types the QR format defines. */
    public enum Security {
        NONE("nopass"), WPA("WPA"), WEP("WEP");

        private final String qrType;

        Security(String qrType) {
            this.qrType = qrType;
        }

        public String qrType() {
            return qrType;
        }

        /** Unknown or blank means WPA, the common case and the safe default. */
        public static Security parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return WPA;
            }
            try {
                return valueOf(raw.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new BadRequestException("Wi-Fi security must be WPA, WEP or NONE.");
            }
        }
    }

    public record Wifi(String ssid, Security security, String password) {
    }

    /** Checks a network the owner is saving. A network with no security keeps no password. */
    public static Wifi validate(String rawSsid, String rawSecurity, String rawPassword) {
        String ssid = rawSsid == null ? "" : rawSsid.strip();
        if (ssid.isEmpty()) {
            throw new BadRequestException("Enter the Wi-Fi network name.");
        }
        if (ssid.getBytes(StandardCharsets.UTF_8).length > MAX_SSID_BYTES) {
            throw new BadRequestException("A Wi-Fi network name can be at most 32 bytes long.");
        }
        if (ssid.chars().anyMatch(Character::isISOControl)) {
            throw new BadRequestException("The Wi-Fi network name contains characters that are not allowed.");
        }
        Security security = Security.parse(rawSecurity);
        if (security == Security.NONE) {
            return new Wifi(ssid, security, null);
        }
        String password = rawPassword == null ? "" : rawPassword;
        if (password.chars().anyMatch(Character::isISOControl)) {
            throw new BadRequestException("The Wi-Fi password contains characters that are not allowed.");
        }
        int length = password.length();
        if (security == Security.WPA && (length < 8 || length > 63)) {
            throw new BadRequestException("A WPA password must be 8 to 63 characters long.");
        }
        if (security == Security.WEP && (length < 5 || length > 26)) {
            throw new BadRequestException("A WEP password must be 5 to 26 characters long.");
        }
        return new Wifi(ssid, security, password);
    }

    /** Escapes the characters the QR format reserves: backslash, semicolon, comma, colon and double quote. */
    static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length() + 4);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' || c == ';' || c == ',' || c == ':' || c == '"') {
                out.append('\\');
            }
            out.append(c);
        }
        return out.toString();
    }

    /** The text to encode in the QR image. */
    public static String qrPayload(String ssid, Security security, String password) {
        StringBuilder sb = new StringBuilder("WIFI:T:").append(security.qrType()).append(";S:").append(escape(ssid)).append(';');
        if (security != Security.NONE && password != null) {
            sb.append("P:").append(escape(password)).append(';');
        }
        return sb.append(';').toString();
    }
}
