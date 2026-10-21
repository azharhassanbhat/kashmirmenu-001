package com.kashmirmenu.app.entity;

import com.kashmirmenu.app.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * An uploaded photo, kept in the database. See V38. The bytes are only ever read through the repository's
 * projection (never by loading this entity for a list), so the menu and other queries do not carry them.
 */
@Entity
@Table(
        name = "stored_images",
        uniqueConstraints = @UniqueConstraint(name = "uk_stored_images_public_key", columnNames = "public_key"))
@Getter
@Setter
@NoArgsConstructor
public class StoredImage extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 32 random hex characters: the unguessable part of the photo's public address. */
    @Column(name = "public_key", nullable = false, length = 32, updatable = false)
    private String publicKey;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "restaurant_id", nullable = false, updatable = false)
    private Restaurant restaurant;

    @Column(name = "content_type", nullable = false, length = 30, updatable = false)
    private String contentType;

    @Column(nullable = false, updatable = false)
    private Integer width;

    @Column(nullable = false, updatable = false)
    private Integer height;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private Integer sizeBytes;

    // MEDIUMBLOB is reported by MySQL as LONGVARBINARY; saying so explicitly keeps schema validation
    // from expecting a different binary type.
    @JdbcTypeCode(SqlTypes.LONGVARBINARY)
    @Column(name = "data", nullable = false, updatable = false, columnDefinition = "mediumblob")
    private byte[] data;
}
