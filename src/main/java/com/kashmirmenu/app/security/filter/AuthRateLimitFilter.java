package com.kashmirmenu.app.security.filter;

import com.kashmirmenu.app.config.RateLimitProperties;
import com.kashmirmenu.app.controller.PublicDinerController;
import com.kashmirmenu.app.service.DinerIdentity;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Minimal in-memory, per-IP fixed-window rate limiter for the unauthenticated
 * auth endpoints (login/register), where brute-forcing or signup abuse would
 * otherwise be unthrottled. Not a substitute for an edge/gateway rate limiter
 * in a multi-instance deployment (state is per-instance, not shared), but
 * stops naive abuse without adding a new dependency.
 */
@Component
@RequiredArgsConstructor
public class AuthRateLimitFilter extends OncePerRequestFilter {

    private final RateLimitProperties rateLimitProperties;

    private final FixedWindowLimiter limiter = new FixedWindowLimiter();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return bucket(request) == null;
    }

    private String bucket(HttpServletRequest request) {
        String path = request.getServletPath();
        // The platform-owner claim is unauthenticated and guards the most privileged account
        // on the platform, so it is throttled with the sign-in endpoints.
        if (path.startsWith("/api/v1/auth/") || path.equals("/api/platform-admin/claim")) {
            return "auth";
        }
        // Order lookups are throttled on GET as well. They are unauthenticated and keyed by a
        // single opaque value, so they are the one public read worth rate limiting - otherwise
        // the token could simply be guessed at speed. Their own, higher bucket: every guest's order
        // screen polls every few seconds, and a dining room behind one address would otherwise use up
        // the allowance for placing orders on reads alone. Anything that writes stays in "public".
        if (path.startsWith("/api/public/orders/")) {
            return "GET".equals(request.getMethod()) ? "track" : "public";
        }
        if (path.startsWith("/api/public/")
                && !"GET".equals(request.getMethod())
                && !"OPTIONS".equals(request.getMethod())) {
            // Landing-page writes that need no sign-in at all get a tighter limit of their own, on top of the
            // generic one below, so a stranger cannot flood a restaurant's feedback or mint rewards members.
            if (tableSegment(path, "/anonymous-feedback") != null) {
                return "feedback";
            }
            if (tableSegment(path, "/rewards/") != null) {
                return "rewards";
            }
            return "public";
        }
        return null;
    }

    /** The table QR in /api/public/tables/{qr}{marker}..., or null when the path is not of that shape. */
    private static String tableSegment(String path, String marker) {
        String prefix = "/api/public/tables/";
        if (!path.startsWith(prefix)) {
            return null;
        }
        String rest = path.substring(prefix.length());
        int slash = rest.indexOf('/');
        if (slash <= 0) {
            return null;
        }
        String tail = rest.substring(slash);
        boolean matches = marker.endsWith("/") ? tail.startsWith(marker) : tail.equals(marker);
        return matches ? rest.substring(0, slash) : null;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String bucket = bucket(request);
        boolean landingWrite = "feedback".equals(bucket) || "rewards".equals(bucket);
        // Landing writes are also ordinary public writes: they count against the generic per-IP ceiling too.
        String generic = landingWrite ? "public" : bucket;
        int windowSeconds = rateLimitProperties.getAuthWindowSeconds();
        int retryAfterSeconds = windowSeconds;

        // Per client IP. For the public bucket this is a high ceiling against abuse, not the everyday
        // limit: a whole restaurant's phones share one wifi address, so a low per-IP limit would let one
        // busy table lock the rest out.
        int ipLimit = switch (generic) {
            case "auth" -> rateLimitProperties.getAuthLimit();
            case "track" -> rateLimitProperties.getPublicTrackLimit();
            default -> rateLimitProperties.getPublicLimit();
        };
        boolean allowed = limiter.tryConsume(generic + ":" + clientIp(request), ipLimit, windowSeconds);

        // The landing-page limit: per IP AND per table, so one table cannot lock the rest of the room out.
        if (allowed && landingWrite) {
            String qr = tableSegment(request.getServletPath(), "feedback".equals(bucket) ? "/anonymous-feedback" : "/rewards/");
            int landingLimit = "feedback".equals(bucket)
                    ? rateLimitProperties.getLandingFeedbackLimit()
                    : rateLimitProperties.getLandingRewardsLimit();
            int landingWindow = rateLimitProperties.getLandingWindowSeconds();
            retryAfterSeconds = landingWindow;
            allowed = limiter.tryConsume(bucket + ":" + clientIp(request) + ":" + qr, landingLimit, landingWindow);
        }

        // A guest at a table is limited by their own token as well, which is the everyday limit: one
        // phone cannot use up the allowance of the others at its table.
        if (allowed && "public".equals(generic)) {
            String guest = guestKey(request);
            if (guest != null) {
                allowed = limiter.tryConsume("guest:" + guest, rateLimitProperties.getPublicGuestLimit(), windowSeconds);
            }
        }

        if (!allowed) {
            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
            response.setContentType("application/json");
            response.getWriter().write(
                    "{\"success\":false,\"message\":\"Too many requests. Please try again later.\"}");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private String clientIp(HttpServletRequest request) {
        // Only honor X-Forwarded-For when explicitly enabled (i.e. the app
        // sits behind a trusted reverse proxy that overwrites/strips any
        // client-supplied value). Otherwise this header is trivially
        // spoofable and would let a client both bypass the limit and exhaust
        // memory by cycling through fake IPs.
        if (rateLimitProperties.isTrustForwardedHeader()) {
            String forwardedFor = request.getHeader("X-Forwarded-For");
            if (forwardedFor != null && !forwardedFor.isBlank()) {
                return forwardedFor.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }

    /**
     * A short fingerprint of the guest's token, so the raw token is never held in memory as a key. Null
     * when the request carries none (joining a table, or someone who is not a guest).
     */
    private static String guestKey(HttpServletRequest request) {
        String token = request.getHeader(PublicDinerController.TOKEN_HEADER);
        if (token == null || token.isBlank() || token.length() > 200) {
            return null;
        }
        return DinerIdentity.hash(token.trim()).substring(0, 24);
    }
}
