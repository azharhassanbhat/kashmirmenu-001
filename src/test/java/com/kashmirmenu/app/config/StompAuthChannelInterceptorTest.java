package com.kashmirmenu.app.config;

import com.kashmirmenu.app.entity.Restaurant;
import com.kashmirmenu.app.entity.Role;
import com.kashmirmenu.app.entity.User;
import com.kashmirmenu.app.enums.RestaurantStatus;
import com.kashmirmenu.app.enums.RoleName;
import com.kashmirmenu.app.repository.RestaurantRepository;
import com.kashmirmenu.app.security.CustomUserDetails;
import com.kashmirmenu.app.security.jwt.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetailsService;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Live-feed subscriptions follow the same approval rule as the REST APIs. */
class StompAuthChannelInterceptorTest {

    private final RestaurantRepository restaurants = mock(RestaurantRepository.class);
    private final StompAuthChannelInterceptor interceptor = new StompAuthChannelInterceptor(
            mock(JwtService.class), mock(UserDetailsService.class), restaurants);

    private Message<?> subscribe(long ownRestaurantId, String destination) {
        Restaurant restaurant = new Restaurant();
        restaurant.setId(ownRestaurantId);
        User user = User.builder().name("Staff").email("s@example.com").password("x")
                .role(Role.builder().name(RoleName.MANAGER).build()).restaurant(restaurant).build();
        CustomUserDetails details = new CustomUserDetails(user);

        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination(destination);
        accessor.setUser(new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities()));
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private void restaurantIs(long id, RestaurantStatus status) {
        when(restaurants.findApprovalStatusById(id)).thenReturn(Optional.of(status));
    }

    @Test
    void anApprovedRestaurantMaySubscribeToItsOwnFeed() {
        restaurantIs(7, RestaurantStatus.APPROVED);
        assertDoesNotThrow(() -> interceptor.preSend(subscribe(7, "/topic/restaurants/7"), mock(MessageChannel.class)));
    }

    @Test
    void aRestaurantThatIsNotApprovedGetsNoLiveFeed() {
        for (RestaurantStatus status : new RestaurantStatus[]{
                RestaurantStatus.PENDING, RestaurantStatus.REJECTED, RestaurantStatus.SUSPENDED}) {
            restaurantIs(7, status);
            assertThrows(StompAuthChannelInterceptor.MessagingAccessDeniedException.class,
                    () -> interceptor.preSend(subscribe(7, "/topic/restaurants/7"), mock(MessageChannel.class)),
                    status.name());
        }
    }

    @Test
    void aMissingRestaurantRowIsRefusedToo() {
        when(restaurants.findApprovalStatusById(7L)).thenReturn(Optional.empty());
        assertThrows(StompAuthChannelInterceptor.MessagingAccessDeniedException.class,
                () -> interceptor.preSend(subscribe(7, "/topic/restaurants/7"), mock(MessageChannel.class)));
    }

    @Test
    void anotherRestaurantsFeedIsStillRefusedWhateverTheStatus() {
        restaurantIs(7, RestaurantStatus.APPROVED);
        assertThrows(StompAuthChannelInterceptor.MessagingAccessDeniedException.class,
                () -> interceptor.preSend(subscribe(7, "/topic/restaurants/8"), mock(MessageChannel.class)));
    }

    private Message<?> subscribeAs(RoleName role, Long restaurantId, String destination) {
        Restaurant restaurant = null;
        if (restaurantId != null) {
            restaurant = new Restaurant();
            restaurant.setId(restaurantId);
        }
        User user = User.builder().name("Someone").email("p@example.com").password("x")
                .role(Role.builder().name(role).build()).restaurant(restaurant).build();
        CustomUserDetails details = new CustomUserDetails(user);

        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination(destination);
        accessor.setUser(new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities()));
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private Message<?> clientFrame(StompCommand command, String destination) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        if (destination != null) {
            accessor.setDestination(destination);
        }
        return MessageBuilder.createMessage("{}".getBytes(), accessor.getMessageHeaders());
    }

    @Test
    void aClientCanNeverPublishOrOpenATransaction() {
        for (StompCommand command : new StompCommand[]{StompCommand.SEND, StompCommand.BEGIN, StompCommand.COMMIT, StompCommand.ABORT}) {
            for (String destination : new String[]{"/topic/restaurants/7", "/topic/restaurants/8", "/topic/platform", "/app/anything", null}) {
                assertThrows(StompAuthChannelInterceptor.MessagingAccessDeniedException.class,
                        () -> interceptor.preSend(clientFrame(command, destination), mock(MessageChannel.class)),
                        command + " to " + destination);
            }
        }
    }

    @Test
    void theFramesAListeningBrowserNeedsAreStillAllowed() {
        for (StompCommand command : new StompCommand[]{StompCommand.UNSUBSCRIBE, StompCommand.DISCONNECT, StompCommand.ACK, StompCommand.NACK}) {
            assertDoesNotThrow(() -> interceptor.preSend(clientFrame(command, null), mock(MessageChannel.class)), command.name());
        }
    }

    @Test
    void thePlatformOwnerMayListenToThePlatformFeed() {
        assertDoesNotThrow(() -> interceptor.preSend(
                subscribeAs(RoleName.PLATFORM_OWNER, null, "/topic/platform"), mock(MessageChannel.class)));
    }

    @Test
    void noRestaurantAccountMayListenToThePlatformFeed() {
        restaurantIs(7, RestaurantStatus.APPROVED);
        for (RoleName role : new RoleName[]{RoleName.RESTAURANT_OWNER, RoleName.MANAGER, RoleName.CHEF,
                RoleName.WAITER, RoleName.CASHIER, RoleName.CUSTOMER}) {
            assertThrows(StompAuthChannelInterceptor.MessagingAccessDeniedException.class,
                    () -> interceptor.preSend(subscribeAs(role, 7L, "/topic/platform"), mock(MessageChannel.class)),
                    role.name());
        }
    }

    @Test
    void aGuestAccountIsRefusedTheStaffFeedEvenOfItsOwnRestaurant() {
        restaurantIs(7, RestaurantStatus.APPROVED);
        assertThrows(StompAuthChannelInterceptor.MessagingAccessDeniedException.class,
                () -> interceptor.preSend(
                        subscribeAs(RoleName.CUSTOMER, 7L, "/topic/restaurants/7"), mock(MessageChannel.class)));
    }

    @Test
    void everyStaffRoleMaySubscribeToItsOwnApprovedFeed() {
        restaurantIs(7, RestaurantStatus.APPROVED);
        for (RoleName role : new RoleName[]{RoleName.ADMIN, RoleName.RESTAURANT_OWNER, RoleName.MANAGER,
                RoleName.CHEF, RoleName.WAITER, RoleName.CASHIER}) {
            assertDoesNotThrow(
                    () -> interceptor.preSend(subscribeAs(role, 7L, "/topic/restaurants/7"), mock(MessageChannel.class)),
                    role.name());
        }
    }

    @Test
    void thePlatformOwnerHasNoRestaurantFeed() {
        assertThrows(StompAuthChannelInterceptor.MessagingAccessDeniedException.class,
                () -> interceptor.preSend(
                        subscribeAs(RoleName.PLATFORM_OWNER, null, "/topic/restaurants/7"), mock(MessageChannel.class)));
    }
}
