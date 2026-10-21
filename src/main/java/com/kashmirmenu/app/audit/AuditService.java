package com.kashmirmenu.app.audit;

public interface AuditService {

    /**
     * Records an action by the currently signed-in user. Safe to call from anywhere: a failure to
     * write the audit row is logged and swallowed, because losing an audit line must not fail the
     * operation that was being audited.
     */
    void record(AuditAction action, String resourceType, Object resourceId, String detail);

    /** Records an action whose actor is known by email but may have no account (a failed sign-in). */
    void recordForEmail(AuditAction action, String email, String detail);
}
