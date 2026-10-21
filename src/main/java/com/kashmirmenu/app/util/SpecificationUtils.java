package com.kashmirmenu.app.util;

import org.springframework.data.jpa.domain.Specification;

import java.util.List;

public final class SpecificationUtils {

    private SpecificationUtils() {
    }

    public static <T> Specification<T> allOf(List<Specification<T>> specifications) {

        if (specifications == null || specifications.isEmpty()) {
            return Specification.unrestricted();
        }

        return Specification.allOf(specifications);
    }
}