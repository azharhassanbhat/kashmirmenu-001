package com.kashmirmenu.app.repository;

import com.kashmirmenu.app.entity.Cart;
import com.kashmirmenu.app.entity.CartItem;
import com.kashmirmenu.app.entity.MenuItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CartItemRepository extends JpaRepository<CartItem, Long> {

    List<CartItem> findByCart(Cart cart);

    /** The cart's line for this dish with exactly these options (see CartItem.optionsKey). */
    Optional<CartItem> findByCartAndMenuItemAndOptionsKey(
            Cart cart,
            MenuItem menuItem,
            String optionsKey);
}