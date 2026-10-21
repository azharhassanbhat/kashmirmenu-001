package com.kashmirmenu.app.config;


import com.kashmirmenu.app.security.filter.AuthRateLimitFilter;
import com.kashmirmenu.app.security.filter.JwtAuthenticationFilter;
import com.kashmirmenu.app.security.filter.RestaurantApprovalFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;

@Configuration
@RequiredArgsConstructor
@EnableMethodSecurity

public class SecurityConfig {

    /** Everyone who runs the restaurant day to day. */
    private static final String[] MANAGEMENT = {"ADMIN", "RESTAURANT_OWNER", "MANAGER"};

    /** Management plus the floor and kitchen staff who serve orders and take payment. */
    private static final String[] STAFF =
            {"ADMIN", "RESTAURANT_OWNER", "MANAGER", "CHEF", "WAITER", "CASHIER"};

    /** The kitchen display: cooks and the floor staff who pass food, plus management. */
    private static final String[] KITCHEN =
            {"ADMIN", "RESTAURANT_OWNER", "MANAGER", "CHEF", "WAITER"};

    private final AuthenticationProvider authenticationProvider;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final AuthRateLimitFilter authRateLimitFilter;
    private final RestaurantApprovalFilter restaurantApprovalFilter;

    /** Actuator's own port. Metrics are reachable only here, and this port is never published. */
    @org.springframework.beans.factory.annotation.Value("${management.server.port:9091}")
    private int managementPort;

    /**
     * Actuator endpoints.
     *
     * <p>Metrics are protected primarily by topology: Actuator listens on MANAGEMENT_PORT, that port
     * is never published to the host, and nginx proxies only /api and /ws - so /actuator/prometheus
     * is not reachable from the internet at all. When the management port differs from the server
     * port, Boot serves Actuator from a child context that this chain does not even filter.
     *
     * <p>This chain exists for the case where the two ports are deliberately or accidentally
     * collapsed into one. Then Actuator rides the application's port, and without this it would be
     * caught by the denyAll fallback (breaking the container healthcheck) or, worse, by some future
     * permissive rule. Requests are allowed only when they arrived on the management port; the same
     * path on the application port is refused.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain actuatorFilterChain(HttpSecurity http) throws Exception {

        http
                .securityMatcher(EndpointRequest.toAnyEndpoint())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .anyRequest().access((authentication, context) ->
                                new org.springframework.security.authorization.AuthorizationDecision(
                                        context.getRequest().getLocalPort() == managementPort)));

        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain securityFilterChain(HttpSecurity http)
            throws Exception {

        http
                .csrf(csrf -> csrf.disable())
                .cors(org.springframework.security.config.Customizer.withDefaults())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authenticationProvider(authenticationProvider)
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .headers(headers -> headers
                        .frameOptions(frame -> frame.deny())
                        .referrerPolicy(referrer -> referrer
                                .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .maxAgeInSeconds(31536000)))
                .authorizeHttpRequests(auth -> auth
                        // Platform administration. Listed first so the namespace is covered
                        // before anything else, with only the one-time claim left open - that has
                        // to work before any platform owner can sign in, and it is single-use,
                        // rate limited and refuses identically on every failure mode.
                        .requestMatchers("/api/platform-admin/claim").permitAll()
                        .requestMatchers("/api/platform-admin/**").hasRole("PLATFORM_OWNER")

                        // Listed before the public /api/v1/auth/** block, which would otherwise
                        // permit it: signing out revokes the caller's own tokens, so it needs a
                        // principal. Method security states the same rule.
                        .requestMatchers("/api/v1/auth/logout").authenticated()
                        .requestMatchers(
                                "/api/v1/auth/**",
                                "/api/public/**",
                                "/swagger-ui/**",
                                "/v3/api-docs/**",

                                "/ws/**",


                                "/css/**",
                                "/js/**",
                                "/images/**"
                        ).permitAll()

                        // Anyone signed in may read who they are.
                        .requestMatchers("/api/v1/me").authenticated()

                        // Floor and kitchen work: taking orders, cooking, taking payment.
                        .requestMatchers("/api/v1/kitchen/**").hasAnyRole(KITCHEN)
                        .requestMatchers("/api/v1/orders/**", "/api/v1/cart/**",
                                "/api/v1/notifications/**", "/api/v1/payments/**",
                                "/api/v1/settlements/**", "/api/v1/table-sessions/**",
                                "/api/v1/service-requests/**", "/api/v1/rewards/**").hasAnyRole(STAFF)

                        // Running the restaurant: menu, tables, stock, money, settings.
                        .requestMatchers("/api/categories/**", "/api/menu-items/**",
                                "/api/v1/tables/**", "/api/v1/inventory/**",
                                "/api/v1/dashboard/**", "/api/v1/coupons/**",
                                "/api/v1/payment-analytics/**", "/api/v1/billing-settings/**", "/api/v1/staff/**", "/api/v1/onboarding/**", "/api/v1/images/**", "/api/v1/feedback/**", "/api/v1/landing/**",
                                "/api/v1/restaurants/**").hasAnyRole(MANAGEMENT)

                        // Restaurant discovery and profile management; the handlers carry their own
                        // finer-grained rules, so this is the floor rather than the whole policy.
                        .requestMatchers("/api/restaurants/**")
                                .hasAnyRole("ADMIN", "RESTAURANT_OWNER", "MANAGER", "CUSTOMER")

                        // Deny by default. Authorization used to be opt-in per controller, so a
                        // controller written without @PreAuthorize was reachable by any signed-in
                        // user, including a self-registered CUSTOMER. A new endpoint now returns 403
                        // until it is deliberately granted, which fails safe and shows up in tests.
                        .anyRequest().denyAll())
                .addFilterBefore(
                        authRateLimitFilter,
                        UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(
                        jwtAuthenticationFilter,
                        UsernamePasswordAuthenticationFilter.class)
                // After the JWT filter, so the caller's identity is known: refuses staff of, and
                // diners at, a restaurant that is not APPROVED.
                .addFilterAfter(
                        restaurantApprovalFilter,
                        JwtAuthenticationFilter.class);

        return http.build();
    }

}