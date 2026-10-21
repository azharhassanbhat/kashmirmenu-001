package com.kashmirmenu.app.entity;

import com.kashmirmenu.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Per-restaurant counter behind the short order numbers (ORD-1, ORD-2, ...). Each restaurant counts
 * independently, so one busy restaurant does not push another's numbers up.
 */
@Entity
@Table(name = "restaurant_order_counters")
@Getter
@Setter
@NoArgsConstructor
public class RestaurantOrderCounter extends BaseEntity implements Persistable<Long> {

    /** The restaurant id is the key: there is exactly one counter per restaurant. */
    @Id
    @Column(name = "restaurant_id")
    private Long restaurantId;

    @Column(name = "next_number", nullable = false)
    private Long nextNumber = 1L;

    /**
     * True until the row has been persisted or loaded.
     *
     * <p>Named for what it records (persisted) rather than isNew, so Lombok's @Getter/@Setter cannot
     * generate a clashing accessor, and excluded from both so only the lifecycle callbacks change it.
     *
     * <p>This is the only entity in the codebase with an ASSIGNED id (every other uses
     * @GeneratedValue), and it has no @Version. Spring Data decides between persist() and merge() by
     * asking isNew(), which for an assigned id it can only answer by guessing from the id being null -
     * and here it never is. It therefore chose merge(), a SELECT followed by an INSERT, for a row we
     * know we are creating. Implementing Persistable removes the guess.
     */
    @Transient
    @Setter(AccessLevel.NONE)
    @Getter(AccessLevel.NONE)
    private boolean persisted = false;

    @Override
    public Long getId() {
        return restaurantId;
    }

    @Override
    public boolean isNew() {
        return !persisted;
    }

    /** Flipped once the row exists, so a later save() of the same instance is an update. */
    @PostPersist
    @PostLoad
    void markNotNew() {
        this.persisted = true;
    }
}
