package com.kashmirmenu.app.repository;

import com.kashmirmenu.app.entity.MenuOptionGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface MenuOptionGroupRepository extends JpaRepository<MenuOptionGroup, Long> {

    /** A dish's groups with their options, in one query. */
    @Query("select distinct g from MenuOptionGroup g left join fetch g.options "
            + "where g.menuItem.id = :menuItemId order by g.displayOrder asc, g.id asc")
    List<MenuOptionGroup> findWithOptionsByMenuItemId(@Param("menuItemId") Long menuItemId);

    /** Every group of many dishes (the whole public menu) in one query, so the menu has no N+1. */
    @Query("select distinct g from MenuOptionGroup g left join fetch g.options "
            + "where g.menuItem.id in :ids order by g.displayOrder asc, g.id asc")
    List<MenuOptionGroup> findWithOptionsByMenuItemIds(@Param("ids") Collection<Long> ids);
}
