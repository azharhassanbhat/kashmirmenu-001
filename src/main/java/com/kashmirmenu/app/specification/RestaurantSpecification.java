package com.kashmirmenu.app.specification;

import com.kashmirmenu.app.entity.Restaurant;
import com.kashmirmenu.app.enums.RestaurantStatus;
import org.springframework.data.jpa.domain.Specification;

public final class RestaurantSpecification {

    private RestaurantSpecification() {
    }

    /** Restaurants cleared to operate; the only ones any customer-facing listing may show. */
    public static Specification<Restaurant> isApproved() {
        return (root, query, cb) -> cb.equal(root.get("approvalStatus"), RestaurantStatus.APPROVED);
    }

    public static Specification<Restaurant> hasName(String name) {

        return (root, query, criteriaBuilder) -> {

            if (name == null || name.isBlank()) {
                return criteriaBuilder.conjunction();
            }

            return criteriaBuilder.like(
                    criteriaBuilder.lower(root.get("name")),
                    "%" + name.toLowerCase() + "%"
            );
        };
    }

    public static Specification<Restaurant> hasCity(String city) {

        return (root, query, criteriaBuilder) -> {

            if (city == null || city.isBlank()) {
                return criteriaBuilder.conjunction();
            }

            return criteriaBuilder.equal(
                    criteriaBuilder.lower(root.get("city")),
                    city.toLowerCase()
            );
        };
    }

    public static Specification<Restaurant> isActive(Boolean active) {

        return (root, query, criteriaBuilder) -> {

            if (active == null) {
                return criteriaBuilder.conjunction();
            }

            return criteriaBuilder.equal(root.get("active"), active);
        };
    }

}