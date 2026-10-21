package com.kashmirmenu.app.service;

import com.kashmirmenu.app.enums.OptionKind;
import com.kashmirmenu.app.exception.BadRequestException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Validates a guest's option choices against a dish's option groups and prices the result.
 *
 * <p>Pure functions with no database or Spring, so the rules that decide what a guest is charged
 * can be tested exhaustively. The client sends only option ids; every name and every price change
 * comes from the groups passed in, which the caller loads from the database. A client can therefore
 * choose among the options that exist but can never invent one or name its own price.
 */
public final class OptionPricing {

    /** A flood limit, and keeps the line's options key inside its column. */
    public static final int MAX_SELECTED = 30;

    private OptionPricing() {
    }

    public record Opt(Long id, String name, BigDecimal priceDelta, boolean available) {
    }

    public record Group(Long id, String name, OptionKind kind, boolean required,
                        Integer maxSelect, List<Opt> options) {
    }

    /** One chosen option as it will be recorded on the cart and order line. */
    public record Chosen(Long optionId, String groupName, String optionName, BigDecimal priceDelta) {
    }

    /**
     * @param unitPrice base price plus every chosen price change, to 2 decimal places
     * @param chosen    the choices in a stable order (by option id)
     * @param key       the chosen ids, sorted, comma separated: two lines of one dish are the same
     *                  line exactly when their keys are equal. Empty when nothing was chosen.
     */
    public record Priced(BigDecimal unitPrice, List<Chosen> chosen, String key) {
    }

    /**
     * The cheapest a dish can ever come to under these groups: the base price plus, for each group,
     * the most the guest could subtract. Used when an editor saves the groups, so a "half portion"
     * discount larger than the dish's price is refused up front instead of quietly making the dish
     * impossible to order. Availability is ignored on purpose: an option switched off today can be
     * switched on tomorrow.
     */
    public static BigDecimal lowestPossiblePrice(BigDecimal basePrice, List<Group> groups) {
        BigDecimal total = basePrice;
        for (Group group : groups) {
            List<BigDecimal> deltas = group.options().stream()
                    .map(Opt::priceDelta)
                    .sorted()
                    .toList();
            if (deltas.isEmpty()) {
                continue;
            }
            BigDecimal lowest = deltas.get(0);
            if (group.kind() == OptionKind.SINGLE) {
                total = total.add(group.required() ? lowest : lowest.min(BigDecimal.ZERO));
            } else {
                int limit = group.maxSelect() == null ? deltas.size() : Math.min(group.maxSelect(), deltas.size());
                BigDecimal sum = BigDecimal.ZERO;
                for (int i = 0; i < limit && deltas.get(i).signum() < 0; i++) {
                    sum = sum.add(deltas.get(i));
                }
                // A required group forces at least one pick even when every option adds money.
                if (group.required() && sum.signum() == 0) {
                    sum = lowest;
                }
                total = total.add(sum);
            }
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    public static Priced price(BigDecimal basePrice, List<Group> groups, Collection<Long> selectedIds) {

        Set<Long> selected = new LinkedHashSet<>();
        if (selectedIds != null) {
            for (Long id : selectedIds) {
                if (id == null) {
                    throw new BadRequestException("That option is not available for this dish.");
                }
                selected.add(id);
            }
        }
        if (selected.size() > MAX_SELECTED) {
            throw new BadRequestException("Too many options were chosen.");
        }

        List<Chosen> chosen = new ArrayList<>();
        Set<Long> matched = new LinkedHashSet<>();

        for (Group group : groups) {
            int count = 0;
            for (Opt option : group.options()) {
                if (!selected.contains(option.id())) {
                    continue;
                }
                if (!option.available()) {
                    throw new BadRequestException(option.name() + " is not available right now.");
                }
                count++;
                matched.add(option.id());
                chosen.add(new Chosen(option.id(), group.name(), option.name(), option.priceDelta()));
            }

            if (group.required() && count == 0) {
                throw new BadRequestException("Please choose " + group.name() + ".");
            }
            if (group.kind() == OptionKind.SINGLE && count > 1) {
                throw new BadRequestException("Please choose only one for " + group.name() + ".");
            }
            if (group.kind() == OptionKind.MULTI && group.maxSelect() != null && count > group.maxSelect()) {
                throw new BadRequestException(
                        "You can choose up to " + group.maxSelect() + " for " + group.name() + ".");
            }
        }

        // Anything chosen that belongs to none of this dish's groups is refused rather than ignored,
        // and the message does not say whether the id exists on some other dish.
        if (!matched.containsAll(selected)) {
            throw new BadRequestException("That option is not available for this dish.");
        }

        chosen.sort(Comparator.comparing(Chosen::optionId));

        BigDecimal unit = basePrice;
        for (Chosen c : chosen) {
            unit = unit.add(c.priceDelta());
        }
        unit = unit.setScale(2, RoundingMode.HALF_UP);

        if (unit.signum() <= 0) {
            throw new BadRequestException("This combination cannot be ordered. Please ask a member of staff.");
        }

        String key = chosen.stream().map(c -> String.valueOf(c.optionId())).collect(Collectors.joining(","));
        return new Priced(unit, List.copyOf(chosen), key);
    }
}
