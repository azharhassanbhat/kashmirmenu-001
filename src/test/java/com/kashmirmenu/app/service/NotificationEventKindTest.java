package com.kashmirmenu.app.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.kashmirmenu.app.enums.NotificationEventKind;
import com.kashmirmenu.app.enums.NotificationType;
import com.kashmirmenu.app.enums.ServiceRequestType;
import org.junit.jupiter.api.Test;

/** The structured kind is what clients pick sounds from, so the mapping is pinned down here. */
class NotificationEventKindTest {

    @Test
    void everyNotificationTypeHasADefaultKind() {
        for (NotificationType type : NotificationType.values()) {
            assertNotNull(NotificationEventKind.defaultFor(type), type.name());
        }
    }

    @Test
    void newOrderIsNamedNewOrderNotOrderPlaced() {
        assertEquals(NotificationEventKind.NEW_ORDER, NotificationEventKind.defaultFor(NotificationType.ORDER_PLACED));
    }

    @Test
    void tableRequestsAreToldApartByTheirType() {
        assertEquals(NotificationEventKind.WAITER_CALL, NotificationEventKind.forNewRequest(ServiceRequestType.CALL_WAITER));
        assertEquals(NotificationEventKind.WAITER_CALL, NotificationEventKind.forNewRequest(ServiceRequestType.ASSISTANCE));
        assertEquals(NotificationEventKind.BILL_REQUEST, NotificationEventKind.forNewRequest(ServiceRequestType.REQUEST_BILL));
        for (ServiceRequestType type : new ServiceRequestType[] {
            ServiceRequestType.DRINKING_WATER, ServiceRequestType.CUTLERY, ServiceRequestType.NAPKINS,
            ServiceRequestType.EXTRA_PLATES, ServiceRequestType.CLEANING, ServiceRequestType.OTHER}) {
            assertEquals(NotificationEventKind.TABLE_REQUEST, NotificationEventKind.forNewRequest(type), type.name());
        }
    }

    @Test
    void rowsWithoutAStoredKindFallBackToTheirType() {
        assertEquals(NotificationEventKind.TABLE_REQUEST,
                NotificationEventKind.defaultFor(NotificationType.SERVICE_REQUEST_CREATED));
        assertEquals(NotificationEventKind.PAYMENT_SUCCESS,
                NotificationEventKind.defaultFor(NotificationType.PAYMENT_SUCCESS));
    }
}
