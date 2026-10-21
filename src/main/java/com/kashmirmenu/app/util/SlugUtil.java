package com.kashmirmenu.app.util;

import com.kashmirmenu.app.repository.RestaurantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SlugUtil {

    private final RestaurantRepository restaurantRepository;

    public String generateSlug(String input) {

        if (input == null || input.isBlank()) {
            return "";
        }

        return input
                .trim()
                .toLowerCase()
                .replaceAll("[^a-z0-9\\s-]", "")
                .replaceAll("\\s+", "-")
                .replaceAll("-+", "-");
    }

    public String generateUniqueSlug(String restaurantName) {

        String slug = generateSlug(restaurantName);

        String uniqueSlug = slug;
        int counter = 2;

        while (restaurantRepository.existsBySlug(uniqueSlug)) {
            uniqueSlug = slug + "-" + counter++;
        }

        return uniqueSlug;
    }
}