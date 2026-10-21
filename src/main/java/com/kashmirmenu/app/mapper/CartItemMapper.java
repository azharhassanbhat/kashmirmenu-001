package com.kashmirmenu.app.mapper;

import com.kashmirmenu.app.entity.CartItemOption;
import com.kashmirmenu.app.dto.response.SelectedOptionResponse;
import com.kashmirmenu.app.dto.response.CartItemResponse;
import com.kashmirmenu.app.entity.CartItem;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper(componentModel = "spring")
public interface CartItemMapper {

    @Mapping(target = "menuItemId", source = "menuItem.id")
    @Mapping(target = "menuItemName", source = "menuItem.name")
    CartItemResponse toResponse(CartItem cartItem);

    List<CartItemResponse> toResponse(List<CartItem> cartItems);

    /** A choice as copied onto the cart line. */
    SelectedOptionResponse toOption(CartItemOption option);
}