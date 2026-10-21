package com.kashmirmenu.app.util;

import com.kashmirmenu.app.exception.BadRequestException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Set;

/**
 * Builds a {@link Pageable} from request parameters.
 *
 * <p>The sort field comes from the caller, so it must never be passed to Spring Data as given. Left open it
 * accepts nested paths such as {@code owner.password}: nothing is injected (the property is resolved against
 * the entity, not pasted into SQL) but a caller could order rows by a column they are not allowed to read and
 * learn it by comparison. Each endpoint therefore names the fields it allows, and anything else is a 400.
 * The page size is bounded for the same reason it always is: one request must not be able to ask for every row.
 */
public final class PageableUtils {

    /** Largest page any list endpoint returns. The admin screens ask for 500 at most. */
    public static final int MAX_PAGE_SIZE = 500;

    public static final Set<String> RESTAURANT_SORT = Set.of("id", "name", "slug", "city", "state", "country", "createdAt");
    public static final Set<String> CATEGORY_SORT = Set.of("id", "name", "displayOrder", "createdAt");
    public static final Set<String> MENU_ITEM_SORT = Set.of("id", "name", "price", "displayOrder", "preparationTime", "createdAt");

    private PageableUtils() {
    }

    public static Pageable createPageable(
            int page,
            int size,
            String sortBy,
            String direction,
            Set<String> allowedSortFields) {

        if (sortBy == null || !allowedSortFields.contains(sortBy)) {
            throw new BadRequestException("You can sort by: " + String.join(", ", new java.util.TreeSet<>(allowedSortFields)) + ".");
        }

        Sort sort = "desc".equalsIgnoreCase(direction)
                ? Sort.by(sortBy).descending()
                : Sort.by(sortBy).ascending();

        return PageRequest.of(Math.max(0, page), Math.min(MAX_PAGE_SIZE, Math.max(1, size)), sort);
    }
}
