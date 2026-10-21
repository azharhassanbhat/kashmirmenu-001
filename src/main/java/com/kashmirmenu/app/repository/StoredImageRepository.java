package com.kashmirmenu.app.repository;

import com.kashmirmenu.app.entity.StoredImage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface StoredImageRepository extends JpaRepository<StoredImage, Long> {

    /** Just the two columns needed to serve a photo: the entity (and its other columns) is never loaded. */
    interface Content {
        byte[] getData();

        String getContentType();
    }

    Optional<Content> findByPublicKey(String publicKey);

    long countByRestaurantId(Long restaurantId);

    /** Whether the photo with this key was uploaded by this restaurant (a QR may only be one of its own). */
    boolean existsByPublicKeyAndRestaurantId(String publicKey, Long restaurantId);

    @Query("select coalesce(sum(i.sizeBytes), 0L) from StoredImage i where i.restaurant.id = :restaurantId")
    long totalBytes(@Param("restaurantId") Long restaurantId);

    /**
     * Photos older than the cut-off that no dish and no restaurant logo refers to. The grace period
     * (applied by the caller) protects a photo uploaded while a form is still open, before it is saved.
     */
    @Query("select i.id from StoredImage i where i.createdAt < :cutoff "
            + "and not exists (select 1 from MenuItem m where m.imageUrl = concat('/api/public/images/', i.publicKey)) "
            + "and not exists (select 1 from Restaurant r where r.logo = concat('/api/public/images/', i.publicKey)) "
            + "and not exists (select 1 from RestaurantPaymentSetting p "
            + "where p.upiQrUrl = concat('/api/public/images/', i.publicKey))")
    List<Long> findUnreferencedOlderThan(@Param("cutoff") LocalDateTime cutoff);
}
