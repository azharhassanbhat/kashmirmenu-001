package com.kashmirmenu.app.config;

import com.kashmirmenu.app.enums.RestaurantStatus;
import com.kashmirmenu.app.enums.RoleName;
import com.kashmirmenu.app.platform.PlatformEventPublisher;
import com.kashmirmenu.app.repository.RestaurantRepository;
import com.kashmirmenu.app.security.CustomUserDetails;
import com.kashmirmenu.app.security.jwt.JwtService;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;

/**
 * Authentication and authorization for the STOMP socket.
 *
 * <p>The HTTP filter chain permits {@code /ws/**} so the handshake can complete, and the JWT servlet
 * filter skips it — so without this interceptor the socket had no access control at all. Every
 * restaurant's live order and payment notifications are published to
 * {@code /topic/restaurants/{restaurantId}}, and restaurant ids are small sequential integers, so
 * anyone able to open the socket could subscribe to any restaurant's feed and watch its trade in real
 * time. The origin check on the endpoint restricts browsers only; a non-browser client sets whatever
 * Origin it likes, so it was never an access control.
 *
 * <p>Three rules:
 * <ul>
 *   <li>Clients cannot publish: SEND (and transaction frames) are refused outright.</li>
 *   <li>CONNECT must carry a valid bearer token, which becomes the session's principal.</li>
 *   <li>SUBSCRIBE to a restaurant topic is allowed only for that user's own restaurant, and only
 *       while that restaurant is APPROVED (a pending, rejected or suspended one gets no live feed,
 *       the same as it gets no REST access; see RestaurantApprovalFilter).</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    static final String RESTAURANT_TOPIC_PREFIX = "/topic/restaurants/";

    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;
    private final RestaurantRepository restaurantRepository;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {

        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }

        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            accessor.setUser(authenticate(accessor));
            return message;
        }

        if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            authorizeSubscription(accessor);
            return message;
        }

        // This socket is one-way: the server publishes, browsers only listen. A SEND frame to a /topic
        // destination is relayed by the simple broker to everyone subscribed to it, so without this rule any
        // signed-in account (a self-registered customer included) could push a forged "Payment received" or
        // "New order" notice into another restaurant's dashboard. Nothing in the app sends from a browser, so
        // every client frame that publishes or opens a transaction is refused.
        StompCommand command = accessor.getCommand();
        if (StompCommand.SEND.equals(command)
                || StompCommand.BEGIN.equals(command)
                || StompCommand.COMMIT.equals(command)
                || StompCommand.ABORT.equals(command)) {
            log.warn("Refused a client {} frame on the notification socket", command);
            throw new MessagingAccessDeniedException("This connection cannot publish messages.");
        }

        return message;
    }

    private Authentication authenticate(StompHeaderAccessor accessor) {

        String header = accessor.getFirstNativeHeader("Authorization");

        if (header == null || !header.startsWith("Bearer ")) {
            throw new MessagingAccessDeniedException("A bearer token is required to open this socket.");
        }

        String jwt = header.substring(7);

        try {
            String username = jwtService.extractUsername(jwt);
            UserDetails userDetails = userDetailsService.loadUserByUsername(username);

            if (!jwtService.isTokenValid(jwt, userDetails)) {
                throw new MessagingAccessDeniedException("That token is not valid.");
            }

            return new UsernamePasswordAuthenticationToken(
                    userDetails, null, userDetails.getAuthorities());

        } catch (JwtException | UsernameNotFoundException | IllegalArgumentException ex) {
            log.debug("Rejecting STOMP CONNECT: {}", ex.getMessage());
            throw new MessagingAccessDeniedException("That token is not valid.");
        }
    }

    private void authorizeSubscription(StompHeaderAccessor accessor) {

        String destination = accessor.getDestination();

        if (PlatformEventPublisher.TOPIC.equals(destination)) {
            authorizePlatformSubscription(accessor);
            return;
        }

        if (destination == null || !destination.startsWith(RESTAURANT_TOPIC_PREFIX)) {
            // Nothing else is published today; refuse rather than allow by omission, so a new topic
            // has to be granted deliberately.
            throw new MessagingAccessDeniedException("That topic cannot be subscribed to.");
        }

        Authentication authentication = (Authentication) accessor.getUser();

        if (authentication == null
                || !(authentication.getPrincipal() instanceof CustomUserDetails details)) {
            throw new MessagingAccessDeniedException("This socket is not authenticated.");
        }

        var restaurant = details.getUser().getRestaurant();

        if (restaurant == null) {
            throw new MessagingAccessDeniedException("This account is not attached to a restaurant.");
        }

        // Staff only. A guest account has no business on the staff feed even if it were ever attached to a restaurant.
        if (details.getUser().getRole().getName() == RoleName.CUSTOMER) {
            throw new MessagingAccessDeniedException("The staff feed is for restaurant staff only.");
        }

        String own = RESTAURANT_TOPIC_PREFIX + restaurant.getId();

        if (!destination.equals(own)) {
            log.warn("Refused STOMP subscription by user {} to {}", details.getUser().getId(), destination);
            throw new MessagingAccessDeniedException("That topic belongs to another restaurant.");
        }

        RestaurantStatus status = restaurantRepository.findApprovalStatusById(restaurant.getId()).orElse(null);
        if (status != RestaurantStatus.APPROVED) {
            throw new MessagingAccessDeniedException("This restaurant is not approved to operate.");
        }
    }

    /** The platform feed is for the platform owner alone: no restaurant account may listen to it. */
    private void authorizePlatformSubscription(StompHeaderAccessor accessor) {

        Authentication authentication = (Authentication) accessor.getUser();

        if (authentication == null
                || !(authentication.getPrincipal() instanceof CustomUserDetails details)
                || details.getUser().getRole().getName() != RoleName.PLATFORM_OWNER) {
            throw new MessagingAccessDeniedException("That topic is for the platform owner only.");
        }
    }

    /** Refusal that Spring turns into a STOMP ERROR frame rather than a server-side stack trace. */
    static class MessagingAccessDeniedException extends org.springframework.messaging.MessagingException {

        MessagingAccessDeniedException(String message) {
            super(message);
        }
    }
}
