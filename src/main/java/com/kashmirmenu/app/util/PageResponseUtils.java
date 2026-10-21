package com.kashmirmenu.app.util;

import com.kashmirmenu.app.common.response.PageResponse;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

public final class PageResponseUtils {

    private PageResponseUtils() {
    }

    public static <T, R> PageResponse<R> fromPage(
            Page<T> page,
            Function<T, R> mapper) {

        List<R> items = page.getContent()
                .stream()
                .map(mapper)
                .toList();

        return PageResponse.<R>builder()
                .items(items)
                .page(page.getNumber())
                .size(page.getSize())
                .totalItems(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .hasNext(page.hasNext())
                .hasPrevious(page.hasPrevious())
                .build();
    }
}