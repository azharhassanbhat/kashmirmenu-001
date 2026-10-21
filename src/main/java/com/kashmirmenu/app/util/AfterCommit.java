package com.kashmirmenu.app.util;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Runs work only once the surrounding transaction has committed (immediately if there is none). */
public final class AfterCommit {

    private AfterCommit() {
    }

    /**
     * Use for records that must describe what really happened, such as the audit trail: written inside
     * the transaction they would be committed on their own and survive a rollback, leaving a line that
     * says something was done when it was not. The action must open its own transaction if it writes
     * (the audit service does), because the outer one is already finished when this runs.
     */
    public static void run(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
