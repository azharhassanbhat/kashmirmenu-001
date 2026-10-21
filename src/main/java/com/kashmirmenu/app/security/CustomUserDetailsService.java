package com.kashmirmenu.app.security;

import com.kashmirmenu.app.entity.User;
import com.kashmirmenu.app.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String username) {

        log.debug("Loading a user for authentication");

        User user = userRepository.findByEmail(username)
                .orElseThrow(() -> {

                    // Not logged with the address: it is attacker-controlled text and personal data, and a log full of
                    // them is an account-enumeration oracle. The failed attempt is counted and audited elsewhere.
                    log.warn("Authentication refused: no such account");

                    return new UsernameNotFoundException("No such account.");
                });

        log.debug("User loaded for authentication");

        return new CustomUserDetails(user);
    }
}