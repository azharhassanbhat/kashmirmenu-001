package com.kashmirmenu.app.mapper;

import com.kashmirmenu.app.dto.response.CartResponse;
import com.kashmirmenu.app.entity.Cart;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper(
        componentModel = "spring",
        uses = CartItemMapper.class
)
public interface CartMapper {

    @Mapping(target = "restaurantId", source = "restaurant.id")
    @Mapping(target = "restaurantTableId", source = "restaurantTable.id")
    @Mapping(target = "couponCode", source = "coupon.code")
    @Mapping(target = "couponName", source = "coupon.name")
    CartResponse toResponse(Cart cart);

    List<CartResponse> toResponse(List<Cart> carts);
}