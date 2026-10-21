package com.kashmirmenu.app.repository;

import com.kashmirmenu.app.entity.CartItemOption;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;

public interface CartItemOptionRepository extends JpaRepository<CartItemOption, Long> {

    /**
     * Cuts the live link from carts to options that are about to be deleted. The cart line keeps the
     * name and price it was given; placement will then refuse it as "no longer available". The same
     * thing the database's ON DELETE SET NULL does, done explicitly so it also holds on H2.
     */
    @Modifying(clearAutomatically = false, flushAutomatically = true)
    @Query("update CartItemOption c set c.option = null where c.option.id in :ids")
    int detachOptions(@Param("ids") Collection<Long> ids);
}
