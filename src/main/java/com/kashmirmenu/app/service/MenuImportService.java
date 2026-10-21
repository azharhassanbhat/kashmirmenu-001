package com.kashmirmenu.app.service;

import com.kashmirmenu.app.audit.AuditAction;
import com.kashmirmenu.app.audit.AuditService;
import com.kashmirmenu.app.dto.request.CategoryRequest;
import com.kashmirmenu.app.dto.request.MenuItemRequest;
import com.kashmirmenu.app.dto.response.MenuImportPreviewResponse;
import com.kashmirmenu.app.dto.response.MenuImportResultResponse;
import com.kashmirmenu.app.entity.Category;
import com.kashmirmenu.app.exception.BadRequestException;
import com.kashmirmenu.app.repository.CategoryRepository;
import com.kashmirmenu.app.repository.MenuItemRepository;
import com.kashmirmenu.app.security.service.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Loading a whole menu from a spreadsheet. Parsing is {@link MenuCsv}; this class applies it to the
 * signed-in restaurant, using the SAME services as the "Add item" screen so an imported dish obeys
 * exactly the rules of one typed in by hand.
 *
 * <ul>
 *   <li><b>Preview changes nothing.</b> It reports what would be created and what already exists.</li>
 *   <li><b>Import parses the file again.</b> It never relies on a preview, so a stale or tampered
 *       preview cannot cause a bad import.</li>
 *   <li><b>All or nothing.</b> A file with any problem imports nothing; a failure part-way rolls the
 *       whole import back.</li>
 *   <li><b>Existing dishes are never overwritten.</b> The same dish name in the same category is
 *       skipped and counted, so importing the same file twice is harmless.</li>
 *   <li>Only the caller's own restaurant is read or written.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class MenuImportService {

    private final CategoryRepository categories;
    private final MenuItemRepository items;
    private final CategoryService categoryService;
    private final MenuItemService menuItemService;
    private final CurrentUserService currentUserService;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public MenuImportPreviewResponse preview(String csv) {
        MenuCsv.Result parsed = MenuCsv.parse(csv);
        if (!parsed.ok()) {
            return new MenuImportPreviewResponse(false, List.of(), parsed.problems(), List.of(), 0, 0);
        }

        Long restaurantId = currentUserService.getCurrentRestaurantId();
        Map<String, Long> categoryIds = new HashMap<>();         // lower-cased name -> id, or absent
        Set<String> missing = new LinkedHashSet<>();
        List<MenuImportPreviewResponse.Row> rows = new ArrayList<>();
        int toCreate = 0, existing = 0;

        for (MenuCsv.Row r : parsed.rows()) {
            String key = r.category().toLowerCase(Locale.ROOT);
            if (!categoryIds.containsKey(key) && !missing.contains(r.category())) {
                var found = categories.findByRestaurantIdAndNameIgnoreCase(restaurantId, r.category());
                if (found.isPresent()) {
                    categoryIds.put(key, found.get().getId());
                } else {
                    missing.add(r.category());
                }
            }
            Long categoryId = categoryIds.get(key);
            boolean exists = categoryId != null
                    && items.existsByRestaurantIdAndCategoryIdAndName(restaurantId, categoryId, r.name());
            if (exists) {
                existing++;
            } else {
                toCreate++;
            }
            rows.add(new MenuImportPreviewResponse.Row(r.line(), r.category(), r.name(), r.price(), r.veg(),
                    r.available(), r.prepMinutes(), exists ? "EXISTS" : "NEW", categoryId == null));
        }
        return new MenuImportPreviewResponse(true, rows, List.of(), List.copyOf(missing), toCreate, existing);
    }

    @Transactional
    public MenuImportResultResponse importMenu(String csv) {
        MenuCsv.Result parsed = MenuCsv.parse(csv);
        if (!parsed.ok()) {
            MenuCsv.Problem first = parsed.problems().get(0);
            throw new BadRequestException("The file has " + parsed.problems().size()
                    + (parsed.problems().size() == 1 ? " problem" : " problems")
                    + " and nothing was imported. "
                    + (first.line() > 0 ? "First: line " + first.line() + ": " : "First: ") + first.message());
        }

        Long restaurantId = currentUserService.getCurrentRestaurantId();
        Map<String, Long> categoryIds = new HashMap<>();
        int created = 0, skipped = 0, categoriesCreated = 0, order = 0;

        for (MenuCsv.Row r : parsed.rows()) {
            String key = r.category().toLowerCase(Locale.ROOT);
            Long categoryId = categoryIds.get(key);
            if (categoryId == null) {
                var found = categories.findByRestaurantIdAndNameIgnoreCase(restaurantId, r.category());
                if (found.isPresent()) {
                    categoryId = found.get().getId();
                } else {
                    CategoryRequest request = new CategoryRequest();
                    request.setName(r.category());
                    request.setActive(true);
                    categoryId = categoryService.createCategory(request).getId();
                    categoriesCreated++;
                }
                categoryIds.put(key, categoryId);
            }

            if (items.existsByRestaurantIdAndCategoryIdAndName(restaurantId, categoryId, r.name())) {
                skipped++;
                continue;
            }

            menuItemService.createMenuItem(MenuItemRequest.builder()
                    .name(r.name())
                    .description(r.description())
                    .price(r.price())
                    .veg(r.veg())
                    .available(r.available())
                    .active(true)
                    .displayOrder(++order)
                    .preparationTime(r.prepMinutes())
                    .categoryId(categoryId)
                    .build());
            created++;
        }

        auditService.record(AuditAction.MENU_IMPORTED, "MENU", restaurantId,
                created + " created, " + skipped + " already existed, " + categoriesCreated + " categories added");
        return new MenuImportResultResponse(created, skipped, categoriesCreated);
    }
}
