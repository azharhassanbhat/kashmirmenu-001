package com.kashmirmenu.app.service;

import com.kashmirmenu.app.entity.DinerSession;
import com.kashmirmenu.app.service.impl.CartServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * A guard for a bug no H2 test can show.
 *
 * <p>CartServiceImpl.getCart used to be marked read-only. It is not read-only: a guest's first look at
 * their cart creates it, and every cart operation takes the owner's row lock first. MySQL's driver
 * refuses data-modifying statements on a read-only connection, so on MySQL the first cart request every
 * guest makes after joining failed, while H2 accepted it and every test passed.
 *
 * <p>So the fact is pinned here, where it fails on every run, rather than only on the MySQL run
 * (./gradlew mysqlTest), which would also catch it but is run less often.
 */
class CartTransactionsTest {

    @Test
    void readingACartMustNotBeReadOnlyBecauseItCreatesTheCartAndLocksItsOwner() throws Exception {
        Method getCart = CartServiceImpl.class.getMethod("getCart", String.class, DinerSession.class);

        Transactional onMethod = getCart.getAnnotation(Transactional.class);
        Transactional onClass = CartServiceImpl.class.getAnnotation(Transactional.class);

        assertFalse(onMethod != null && onMethod.readOnly(),
                "getCart creates the cart and takes a row lock: it cannot be a read-only transaction");
        assertFalse(onMethod == null && onClass != null && onClass.readOnly(),
                "and it must not inherit read-only from the class either");
    }
}
