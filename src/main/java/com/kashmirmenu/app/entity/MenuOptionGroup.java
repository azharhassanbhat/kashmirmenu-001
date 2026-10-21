package com.kashmirmenu.app.entity;

import com.kashmirmenu.app.common.BaseEntity;
import com.kashmirmenu.app.enums.OptionKind;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.BatchSize;

import java.util.ArrayList;
import java.util.List;

/** A set of choices on a dish: "Portion", "Extras", "Spice level". See V35. */
@Entity
@Table(name = "menu_option_groups")
@Getter
@Setter
@NoArgsConstructor
public class MenuOptionGroup extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "menu_item_id", nullable = false, updatable = false)
    private MenuItem menuItem;

    @Column(nullable = false, length = 80)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private OptionKind kind = OptionKind.SINGLE;

    @Column(nullable = false)
    private Boolean required = false;

    @Column(name = "max_select")
    private Integer maxSelect;

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder = 0;

    @OneToMany(mappedBy = "group", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("displayOrder ASC, id ASC")
    @BatchSize(size = 50)
    private List<MenuOption> options = new ArrayList<>();
}
