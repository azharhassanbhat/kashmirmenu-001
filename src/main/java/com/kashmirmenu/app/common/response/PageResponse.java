package com.kashmirmenu.app.common.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PageResponse<T> {

    @Builder.Default
    private List<T> items = List.of();

    private int page;

    private int size;

    private long totalItems;

    private int totalPages;

    private boolean hasNext;

    private boolean hasPrevious;
}