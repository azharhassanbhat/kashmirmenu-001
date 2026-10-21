package com.kashmirmenu.app.service;

import com.kashmirmenu.app.dto.request.MenuOptionsRequest;
import com.kashmirmenu.app.dto.response.OptionGroupResponse;
import com.kashmirmenu.app.dto.response.OptionResponse;
import com.kashmirmenu.app.entity.MenuItem;
import com.kashmirmenu.app.entity.MenuOption;
import com.kashmirmenu.app.entity.MenuOptionGroup;
import com.kashmirmenu.app.enums.OptionKind;
import com.kashmirmenu.app.exception.BadRequestException;
import com.kashmirmenu.app.exception.ResourceNotFoundException;
import com.kashmirmenu.app.repository.CartItemOptionRepository;
import com.kashmirmenu.app.repository.MenuItemRepository;
import com.kashmirmenu.app.repository.MenuOptionGroupRepository;
import com.kashmirmenu.app.security.service.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Managing a dish's portion sizes, add-ons and spice levels. Staff only, and only for dishes of the
 * caller's own restaurant: a dish id from another restaurant answers "not found".
 *
 * <p>Saving is a full replace reconciled by id, so ids stay stable across edits. That matters: a
 * guest's cart line points at the option it was built from, and keeping the id lets placement
 * notice a later switch-off. Options removed in an edit are first cut loose from any cart that
 * still references them; those carts keep the name and price the guest saw.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class MenuOptionService {

    static final int MAX_OPTIONS_PER_DISH = 60;

    private final MenuItemRepository menuItems;
    private final MenuOptionGroupRepository groups;
    private final CartItemOptionRepository cartOptions;
    private final CurrentUserService currentUserService;

    @Transactional(readOnly = true)
    public List<OptionGroupResponse> get(Long menuItemId) {
        requireOwnItem(menuItemId);
        return groups.findWithOptionsByMenuItemId(menuItemId).stream().map(MenuOptionService::toResponse).toList();
    }

    public List<OptionGroupResponse> replace(Long menuItemId, MenuOptionsRequest request) {
        MenuItem item = requireOwnItem(menuItemId);

        List<MenuOptionGroup> existing = groups.findWithOptionsByMenuItemId(menuItemId);
        Map<Long, MenuOptionGroup> byId = new HashMap<>();
        existing.forEach(g -> byId.put(g.getId(), g));

        validate(item, request);

        List<MenuOptionGroup> keptGroups = new ArrayList<>();
        List<Long> removedOptionIds = new ArrayList<>();
        int groupOrder = 0;

        for (MenuOptionsRequest.Group in : request.groups()) {
            MenuOptionGroup group;
            if (in.id() == null) {
                group = new MenuOptionGroup();
                group.setMenuItem(item);
            } else {
                group = byId.remove(in.id());
                if (group == null) {
                    throw new ResourceNotFoundException("Option group not found.");
                }
            }
            group.setName(in.name().strip());
            group.setKind(in.kind());
            group.setRequired(Boolean.TRUE.equals(in.required()));
            group.setMaxSelect(in.kind() == OptionKind.MULTI ? in.maxSelect() : null);
            group.setDisplayOrder(groupOrder++);

            Map<Long, MenuOption> optionsById = new HashMap<>();
            group.getOptions().forEach(o -> optionsById.put(o.getId(), o));
            Set<Long> keptOptionIds = new HashSet<>();
            int optionOrder = 0;

            for (MenuOptionsRequest.Option o : in.options()) {
                MenuOption option;
                if (o.id() == null) {
                    option = new MenuOption();
                    option.setGroup(group);
                    group.getOptions().add(option);
                } else {
                    option = optionsById.get(o.id());
                    if (option == null) {
                        throw new ResourceNotFoundException("Option not found.");
                    }
                    keptOptionIds.add(o.id());
                }
                option.setName(o.name().strip());
                option.setPriceDelta(o.priceDelta().setScale(2, java.math.RoundingMode.HALF_UP));
                option.setAvailable(o.available() == null || o.available());
                option.setDefaultOption(Boolean.TRUE.equals(o.defaultOption()));
                option.setDisplayOrder(optionOrder++);
            }

            for (MenuOption o : new ArrayList<>(group.getOptions())) {
                if (o.getId() != null && !keptOptionIds.contains(o.getId())) {
                    removedOptionIds.add(o.getId());
                    group.getOptions().remove(o);
                }
            }
            keptGroups.add(group);
        }

        // Whole groups that were left out of the request are removed, with all their options.
        for (MenuOptionGroup gone : byId.values()) {
            gone.getOptions().forEach(o -> removedOptionIds.add(o.getId()));
        }
        if (!removedOptionIds.isEmpty()) {
            cartOptions.detachOptions(removedOptionIds);
        }
        groups.deleteAll(byId.values());
        List<MenuOptionGroup> saved = groups.saveAll(keptGroups);
        groups.flush();

        return saved.stream().map(MenuOptionService::toResponse).toList();
    }

    /** Sold out / back in stock for one option, without resending the whole set. */
    public List<OptionGroupResponse> setAvailability(Long menuItemId, Long optionId, boolean available) {
        requireOwnItem(menuItemId);
        List<MenuOptionGroup> all = groups.findWithOptionsByMenuItemId(menuItemId);
        MenuOption target = all.stream()
                .flatMap(g -> g.getOptions().stream())
                .filter(o -> optionId.equals(o.getId()))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Option not found."));
        target.setAvailable(available);
        return all.stream().map(MenuOptionService::toResponse).toList();
    }

    // ---------------------------------------------------------------- rules

    private void validate(MenuItem item, MenuOptionsRequest request) {
        Set<String> groupNames = new HashSet<>();
        int total = 0;

        for (MenuOptionsRequest.Group g : request.groups()) {
            if (!groupNames.add(g.name().strip().toLowerCase(Locale.ROOT))) {
                throw new BadRequestException("Two groups are both called \"" + g.name().strip() + "\".");
            }
            Set<String> optionNames = new HashSet<>();
            int defaults = 0;
            for (MenuOptionsRequest.Option o : g.options()) {
                total++;
                if (!optionNames.add(o.name().strip().toLowerCase(Locale.ROOT))) {
                    throw new BadRequestException(
                            "\"" + g.name().strip() + "\" has two options called \"" + o.name().strip() + "\".");
                }
                if (Boolean.TRUE.equals(o.defaultOption())) {
                    defaults++;
                }
            }
            if (g.kind() == OptionKind.SINGLE && defaults > 1) {
                throw new BadRequestException(
                        "\"" + g.name().strip() + "\" can only have one pre-selected option.");
            }
        }
        if (total > MAX_OPTIONS_PER_DISH) {
            throw new BadRequestException("A dish can have at most " + MAX_OPTIONS_PER_DISH + " options in total.");
        }

        // The cheapest combination must still be a real price.
        BigDecimal lowest = OptionPricing.lowestPossiblePrice(item.getPrice(), toRulesFromRequest(request));
        if (lowest.signum() <= 0) {
            throw new BadRequestException(
                    "These price changes could bring " + item.getName()
                            + " to zero or less. Reduce the discounts or raise the dish's price.");
        }
    }

    private static List<OptionPricing.Group> toRulesFromRequest(MenuOptionsRequest request) {
        return request.groups().stream()
                .map(g -> new OptionPricing.Group(
                        g.id(), g.name(), g.kind(), Boolean.TRUE.equals(g.required()),
                        g.kind() == OptionKind.MULTI ? g.maxSelect() : null,
                        g.options().stream()
                                .map(o -> new OptionPricing.Opt(
                                        o.id(), o.name(), o.priceDelta(), o.available() == null || o.available()))
                                .toList()))
                .toList();
    }

    private MenuItem requireOwnItem(Long menuItemId) {
        return menuItems.findByIdAndRestaurant(menuItemId, currentUserService.getCurrentRestaurant())
                .orElseThrow(() -> new ResourceNotFoundException("Menu item not found."));
    }

    // ------------------------------------------------------------- mapping

    public static OptionGroupResponse toResponse(MenuOptionGroup g) {
        return new OptionGroupResponse(
                g.getId(),
                g.getName(),
                g.getKind(),
                Boolean.TRUE.equals(g.getRequired()),
                g.getMaxSelect(),
                g.getOptions().stream()
                        .map(o -> new OptionResponse(
                                o.getId(),
                                o.getName(),
                                o.getPriceDelta(),
                                Boolean.TRUE.equals(o.getAvailable()),
                                Boolean.TRUE.equals(o.getDefaultOption())))
                        .toList());
    }

    /** The server-side rule view of a dish's stored groups, for pricing and validating a guest's choices. */
    public static List<OptionPricing.Group> toRules(List<MenuOptionGroup> stored) {
        return stored.stream()
                .map(g -> new OptionPricing.Group(
                        g.getId(), g.getName(), g.getKind(), Boolean.TRUE.equals(g.getRequired()),
                        g.getMaxSelect(),
                        g.getOptions().stream()
                                .map(o -> new OptionPricing.Opt(
                                        o.getId(), o.getName(), o.getPriceDelta(),
                                        Boolean.TRUE.equals(o.getAvailable())))
                                .toList()))
                .toList();
    }
}
