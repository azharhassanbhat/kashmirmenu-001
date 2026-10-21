package com.kashmirmenu.app.service;

import com.kashmirmenu.app.dto.response.TagResponse;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.function.Function;

/**
 * Allergens and dietary labels are stored as comma-separated enum codes. NULL means the restaurant
 * has not declared anything; "" means it declared that none apply. Unknown codes in storage are
 * skipped rather than failing a whole menu read.
 */
public final class MenuTags {

    private MenuTags() {
    }

    /** Declared codes in the enum's own order, or an empty list if undeclared. */
    public static <E extends Enum<E>> List<E> parse(String csv, Class<E> type) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        EnumSet<E> found = EnumSet.noneOf(type);
        for (String part : csv.split(",")) {
            try {
                found.add(Enum.valueOf(type, part.strip()));
            } catch (IllegalArgumentException ignored) {
                // a code removed from the vocabulary: skip it
            }
        }
        return new ArrayList<>(found);
    }

    /** The storage form: sorted by the enum's order, no duplicates. null in means "leave undeclared". */
    public static <E extends Enum<E>> String join(Collection<E> values, Class<E> type) {
        if (values == null) {
            return null;
        }
        if (values.isEmpty()) {
            return "";
        }
        EnumSet<E> set = EnumSet.noneOf(type);
        set.addAll(values);
        StringBuilder sb = new StringBuilder();
        for (E e : set) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(e.name());
        }
        return sb.toString();
    }

    public static <E extends Enum<E>> List<TagResponse> tags(
            List<E> values, Function<E, String> label) {
        return values.stream().map(e -> new TagResponse(e.name(), label.apply(e))).toList();
    }
}
