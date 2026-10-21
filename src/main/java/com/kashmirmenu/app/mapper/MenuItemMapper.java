package com.kashmirmenu.app.mapper;

import com.kashmirmenu.app.dto.request.MenuItemRequest;
import com.kashmirmenu.app.enums.Allergen;
import com.kashmirmenu.app.enums.DietaryLabel;
import com.kashmirmenu.app.exception.BadRequestException;
import com.kashmirmenu.app.service.ImageUrlRules;
import com.kashmirmenu.app.service.MenuTags;
import java.util.List;
import com.kashmirmenu.app.dto.response.MenuItemResponse;
import com.kashmirmenu.app.entity.MenuItem;
import org.springframework.stereotype.Component;

@Component
public class MenuItemMapper {

    public MenuItem toEntity(MenuItemRequest request) {

        if (request == null) {
            return null;
        }

        MenuItem menuItem = new MenuItem();

        mapRequestToEntity(menuItem, request);

        return menuItem;
    }

    public MenuItemResponse toResponse(MenuItem menuItem) {

        if (menuItem == null) {
            return null;
        }

        MenuItemResponse.MenuItemResponseBuilder builder =
                MenuItemResponse.builder()
                        .id(menuItem.getId())
                        .name(menuItem.getName())
                        .description(menuItem.getDescription())
                        .price(menuItem.getPrice())
                        .imageUrl(menuItem.getImageUrl())
                        .veg(menuItem.getVeg())
                        .available(menuItem.getAvailable())
                        .active(menuItem.getActive())
                        .displayOrder(menuItem.getDisplayOrder())
                        .preparationTime(menuItem.getPreparationTime())
                        .chefPick(Boolean.TRUE.equals(menuItem.getChefPick()))
                        .todaysSpecial(Boolean.TRUE.equals(menuItem.getTodaysSpecial()))
                        .allergenInfoProvided(menuItem.getAllergens() != null)
                        .allergens(MenuTags.tags(
                                MenuTags.parse(menuItem.getAllergens(), Allergen.class), Allergen::label))
                        .dietaryLabels(MenuTags.tags(
                                MenuTags.parse(menuItem.getDietaryLabels(), DietaryLabel.class),
                                DietaryLabel::label));

        if (menuItem.getRestaurant() != null) {
            builder.restaurantId(menuItem.getRestaurant().getId());
            builder.restaurantName(menuItem.getRestaurant().getName());
        }

        if (menuItem.getCategory() != null) {
            builder.categoryId(menuItem.getCategory().getId());
            builder.categoryName(menuItem.getCategory().getName());
        }

        return builder.build();
    }

    private void mapRequestToEntity(MenuItem menuItem,
                                    MenuItemRequest request) {

        menuItem.setName(request.getName());
        menuItem.setDescription(request.getDescription());
        menuItem.setPrice(request.getPrice());
        // Only an address that is being CHANGED is checked, so a restaurant with an older link can still
        // edit a dish's price. A blank address means no photo.
        ImageUrlRules.requireValidIfChanged(request.getImageUrl(), menuItem.getImageUrl());
        menuItem.setImageUrl(request.getImageUrl() == null || request.getImageUrl().isBlank()
                ? null
                : request.getImageUrl().strip());
        menuItem.setVeg(request.getVeg());
        menuItem.setAvailable(request.getAvailable());
        menuItem.setActive(request.getActive());
        menuItem.setDisplayOrder(request.getDisplayOrder());
        menuItem.setPreparationTime(request.getPreparationTime());

        // Added in Stage B. Each is applied only when the request carries it, so a client that
        // predates them (or sends a partial update) leaves declared allergens and flags untouched.
        if (request.getChefPick() != null) {
            menuItem.setChefPick(request.getChefPick());
        }
        if (request.getTodaysSpecial() != null) {
            menuItem.setTodaysSpecial(request.getTodaysSpecial());
        }
        if (request.getAllergens() != null) {
            menuItem.setAllergens(MenuTags.join(request.getAllergens(), Allergen.class));
        }
        if (request.getDietaryLabels() != null) {
            menuItem.setDietaryLabels(MenuTags.join(request.getDietaryLabels(), DietaryLabel.class));
        }

        // A dish cannot be both meat and vegan/Jain. Refused rather than quietly corrected, because
        // either the veg flag or the label is wrong and only the restaurant knows which.
        if (Boolean.FALSE.equals(menuItem.getVeg())) {
            List<DietaryLabel> labels = MenuTags.parse(menuItem.getDietaryLabels(), DietaryLabel.class);
            if (labels.contains(DietaryLabel.VEGAN) || labels.contains(DietaryLabel.JAIN)) {
                throw new BadRequestException(
                        "A non-vegetarian dish cannot be labelled Vegan or Jain. Check the veg setting.");
            }
        }
    }

    public void updateEntity(MenuItem menuItem,
                             MenuItemRequest request) {

        if (menuItem == null || request == null) {
            return;
        }

        mapRequestToEntity(menuItem, request);
    }
}