package com.kashmirmenu.app.service;

import com.kashmirmenu.app.entity.Order;
import com.kashmirmenu.app.entity.OrderStatusEvent;
import com.kashmirmenu.app.enums.CancelReason;
import com.kashmirmenu.app.enums.OrderStatus;
import com.kashmirmenu.app.repository.OrderStatusEventRepository;
import com.kashmirmenu.app.security.service.CurrentUserService;
import org.springframework.stereotype.Component;

/**
 * Writes an order's status history. Called from every place an order's status can change (placing it,
 * the four kitchen steps, and the status endpoint that also cancels), so the record has no gaps.
 *
 * <p>Re-applying the status an order already has is a retry, not a change, and records nothing: a
 * double-tapped "Ready" must not appear twice in the history.
 */
@Component
public class OrderHistory {

    private final OrderStatusEventRepository events;
    private final CurrentUserService currentUserService;

    public OrderHistory(OrderStatusEventRepository events, CurrentUserService currentUserService) {
        this.events = events;
        this.currentUserService = currentUserService;
    }

    /** The order being placed, by a guest (their display name) or by staff. */
    public void placed(Order order, String actorLabel) {
        save(order, null, OrderStatus.PENDING, actorLabel, null, null);
    }

    /** A status change by the signed-in member of staff. A no-op when nothing changed. */
    public void changed(Order order, OrderStatus from, OrderStatus to) {
        changed(order, from, to, null, null);
    }

    public void changed(Order order, OrderStatus from, OrderStatus to, CancelReason reason, String note) {
        if (from == to) {
            return;
        }
        save(order, from, to, currentUserService.getCurrentUser().getEmail(), reason, note);
    }

    private void save(Order order, OrderStatus from, OrderStatus to, String actor, CancelReason reason, String note) {
        OrderStatusEvent e = new OrderStatusEvent();
        e.setOrder(order);
        e.setFromStatus(from);
        e.setToStatus(to);
        e.setActorLabel(actor == null || actor.isBlank() ? "unknown" : (actor.length() > 120 ? actor.substring(0, 120) : actor));
        e.setReason(reason);
        e.setNote(note);
        events.save(e);
    }
}
