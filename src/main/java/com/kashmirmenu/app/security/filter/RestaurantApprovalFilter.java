package com.kashmirmenu.app.security.filter;

import com.kashmirmenu.app.enums.RestaurantStatus;
import com.kashmirmenu.app.repository.RestaurantRepository;
import com.kashmirmenu.app.security.CustomUserDetails;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one place that enforces restaurant approval. Only an APPROVED restaurant may operate:
 *
 * <ul>
 *   <li><b>Staff.</b> A signed-in account that belongs to a restaurant which is PENDING, REJECTED or
 *       SUSPENDED is refused (403, code {@code RESTAURANT_NOT_APPROVED}) on every restaurant-scoped
 *       API. {@code /api/v1/me} and {@code /api/v1/auth/**} stay open, so the owner can still sign in,
 *       see the status and reason, and sign out.</li>
 *   <li><b>Diners.</b> The public QR flow ({@code /api/public/tables/{qr}/**}) answers 404 for a table
 *       of a restaurant that is not approved - the same answer as an unknown QR, so the status of a
 *       restaurant is not revealed to anonymous callers.</li>
 * </ul>
 *
 * Doing this in a filter, rather than in each service, means a new endpoint is covered by default
 * instead of by remembering to ask. The status is read fresh from the database on each request (one
 * indexed lookup), so a suspension takes effect on the very next call, not at token expiry.
 *
 * <p>Deliberately not gated: guests settling or tracking an order they already placed
 * ({@code /api/public/orders/by-token/**}), so a suspension never strands an unpaid bill.
 */
@Component
@RequiredArgsConstructor
public class RestaurantApprovalFilter extends OncePerRequestFilter {

    public static final String CODE = "RESTAURANT_NOT_APPROVED";

    private static final Pattern PUBLIC_TABLE = Pattern.compile("^/api/public/tables/([^/]+)(?:/.*)?$");

    private final RestaurantRepository restaurantRepository;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        // getRequestURI, not getServletPath: the latter is empty under MockMvc and behind some mappings.
        String path = request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && path.startsWith(context)) {
            path = path.substring(context.length());
        }

        Matcher table = PUBLIC_TABLE.matcher(path);
        if (table.matches()) {
            Optional<RestaurantStatus> status = restaurantRepository.findApprovalStatusByTableQr(table.group(1));
            if (status.isPresent() && status.get() != RestaurantStatus.APPROVED) {
                write(response, HttpStatus.NOT_FOUND, "Table not found", null);
                return;
            }
            chain.doFilter(request, response);
            return;
        }

        if (isRestaurantScoped(path)) {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.getPrincipal() instanceof CustomUserDetails principal) {
                Long restaurantId = principal.getRestaurantId();
                if (restaurantId != null) {
                    RestaurantStatus status = restaurantRepository.findApprovalStatusById(restaurantId)
                            .orElse(null);
                    if (status != null && status != RestaurantStatus.APPROVED) {
                        write(response, HttpStatus.FORBIDDEN, messageFor(status), status);
                        return;
                    }
                }
            }
        }

        chain.doFilter(request, response);
    }

    /** Authenticated APIs other than identity, sign-in/out and the platform owner's own area. */
    private static boolean isRestaurantScoped(String path) {
        if (!path.startsWith("/api/")) {
            return false;
        }
        return !(path.equals("/api/v1/me")
                || path.startsWith("/api/v1/auth/")
                || path.equals("/api/v1/auth")
                || path.startsWith("/api/platform-admin/")
                || path.startsWith("/api/public/"));
    }

    private static String messageFor(RestaurantStatus status) {
        return switch (status) {
            case PENDING -> "Your restaurant is awaiting approval and cannot operate yet.";
            case REJECTED -> "Your restaurant registration was rejected.";
            case SUSPENDED -> "Your restaurant is suspended.";
            case APPROVED -> "";
        };
    }

    /** Fixed strings only: nothing request- or user-supplied is ever written into this body. */
    private static void write(HttpServletResponse response, HttpStatus status, String message,
                              RestaurantStatus restaurantStatus) throws IOException {

        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        StringBuilder body = new StringBuilder("{\"success\":false,\"message\":\"")
                .append(message).append("\"");
        if (restaurantStatus != null) {
            body.append(",\"code\":\"").append(CODE).append("\",\"restaurantStatus\":\"")
                    .append(restaurantStatus.name()).append("\"");
        }
        body.append(",\"errors\":[],\"timestamp\":\"").append(Instant.now()).append("\"}");

        response.getWriter().write(body.toString());
    }
}
