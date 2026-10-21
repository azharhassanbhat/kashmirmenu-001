package com.kashmirmenu.app.security;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.kashmirmenu.app.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Signing in with an address that has no account must not write that address to the log: it is personal data
 * the person typed (or an attacker made up), and a log full of them is an account-enumeration oracle.
 */
class NoPersonalDataInLogsTest {

    @Test
    void anUnknownEmailIsNotWrittenToTheLogOrTheException() {
        UserRepository users = mock(UserRepository.class);
        when(users.findByEmail(anyString())).thenReturn(Optional.empty());
        CustomUserDetailsService service = new CustomUserDetailsService(users);

        Logger logger = (Logger) LoggerFactory.getLogger(CustomUserDetailsService.class);
        Level before = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            String probe = "victim.person@example.com\nFAKE LOG LINE";
            UsernameNotFoundException ex = assertThrows(UsernameNotFoundException.class, () -> service.loadUserByUsername(probe));
            assertFalse(ex.getMessage().contains("victim"), "the exception message must not echo the address");
            for (ILoggingEvent event : appender.list) {
                assertFalse(event.getFormattedMessage().contains("victim"), "logged: " + event.getFormattedMessage());
                assertFalse(event.getFormattedMessage().contains("FAKE LOG LINE"), "logged: " + event.getFormattedMessage());
            }
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(before);
        }
    }
}
