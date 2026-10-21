package com.kashmirmenu.app.audit;

import com.kashmirmenu.app.config.BusinessTime;
import com.kashmirmenu.app.entity.User;
import com.kashmirmenu.app.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditServiceImpl implements AuditService {

    private final AuditRepository auditRepository;
    private final BusinessTime businessTime;

    /**
     * In its own transaction. A failed sign-in is audited on a path that then throws, and a rolled
     * back operation must not take its audit trail with it - the record of the attempt is the point.
     *
     * <p>The annotation sits on the public entry points, not on the private writer: a call from
     * inside this bean would bypass the proxy and silently join the caller's transaction instead.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AuditAction action, String resourceType, Object resourceId, String detail) {

        AuditEntry entry = base(action, detail);
        entry.setResourceType(resourceType);
        entry.setResourceId(resourceId == null ? null : String.valueOf(resourceId));

        User actor = currentUser();
        if (actor != null) {
            entry.setActorUserId(actor.getId());
            entry.setActorEmail(actor.getEmail());
            entry.setActorRole(actor.getRole() == null ? null : actor.getRole().getName().name());
        }

        save(entry);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordForEmail(AuditAction action, String email, String detail) {

        AuditEntry entry = base(action, detail);
        entry.setActorEmail(email);

        save(entry);
    }

    private void save(AuditEntry entry) {
        try {
            auditRepository.save(entry);
        } catch (RuntimeException ex) {
            // Never fail the audited operation because its audit line could not be stored.
            log.error("Could not write audit entry {}: {}", entry.getAction(), ex.getMessage());
        }
    }

    private AuditEntry base(AuditAction action, String detail) {
        AuditEntry entry = new AuditEntry();
        entry.setOccurredAt(businessTime.now());
        entry.setAction(action);
        entry.setDetail(trim(detail));
        entry.setIpAddress(clientIp());
        return entry;
    }

    private static User currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof CustomUserDetails details) {
            return details.getUser();
        }
        return null;
    }

    private static String clientIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            HttpServletRequest request = attributes.getRequest();
            // Deliberately the socket address: X-Forwarded-For is client-controlled unless a trusted
            // proxy overwrites it, and an audit log is the wrong place to record a spoofable value.
            return request.getRemoteAddr();
        }
        return null;
    }

    private static String trim(String detail) {
        if (detail == null) {
            return null;
        }
        return detail.length() <= 500 ? detail : detail.substring(0, 497) + "...";
    }
}
