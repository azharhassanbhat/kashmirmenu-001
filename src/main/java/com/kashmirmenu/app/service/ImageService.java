package com.kashmirmenu.app.service;

import com.kashmirmenu.app.dto.response.ImageUploadResponse;
import com.kashmirmenu.app.entity.Restaurant;
import com.kashmirmenu.app.entity.StoredImage;
import com.kashmirmenu.app.exception.ConflictException;
import com.kashmirmenu.app.repository.StoredImageRepository;
import com.kashmirmenu.app.security.service.CurrentUserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Storing and serving uploaded photos. Decoding a photo is memory-hungry (a 12 megapixel image is about
 * 48 MB of pixels), so only a couple are processed at once and the rest wait briefly; a restaurant has a
 * storage quota; and a photo is only ever stored in the safe form {@link ImageProcessor} produces.
 *
 * <p>Processing is deliberately NOT inside a database transaction: it is CPU and memory work that can take
 * a second, and a transaction would hold a database connection for all of it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImageService {

    /** How many photos are decoded at the same moment. The rest wait up to {@link #WAIT_SECONDS}. */
    static final int CONCURRENT_PROCESSING = 2;
    static final int WAIT_SECONDS = 15;

    private final StoredImageRepository images;
    private final CurrentUserService currentUserService;
    private final Semaphore slots = new Semaphore(CONCURRENT_PROCESSING);

    /** The served form of a photo. */
    public record Content(byte[] bytes, String contentType) {
    }

    public ImageUploadResponse store(byte[] raw) {
        Restaurant restaurant = currentUserService.getCurrentRestaurant();

        ImageProcessor.Processed processed = processWithLimit(raw);

        // Checked after processing, against the size that will really be stored, not the upload's.
        ImageQuota.check(
                images.totalBytes(restaurant.getId()),
                images.countByRestaurantId(restaurant.getId()),
                processed.jpeg().length);

        StoredImage image = new StoredImage();
        image.setPublicKey(ImageKeys.newKey());
        image.setRestaurant(restaurant);
        image.setContentType("image/jpeg");
        image.setWidth(processed.width());
        image.setHeight(processed.height());
        image.setSizeBytes(processed.jpeg().length);
        image.setData(processed.jpeg());
        images.save(image);

        return new ImageUploadResponse(
                ImageKeys.urlFor(image.getPublicKey()), processed.width(), processed.height(), processed.jpeg().length);
    }

    @Transactional(readOnly = true)
    public Optional<Content> find(String key) {
        if (!ImageKeys.isValid(key)) {
            return Optional.empty();       // never touch the database for a malformed address
        }
        return images.findByPublicKey(key).map(c -> new Content(c.getData(), c.getContentType()));
    }

    /** Deletes photos older than the cut-off that nothing refers to. Returns how many were removed. */
    @Transactional
    public int deleteUnreferencedOlderThan(LocalDateTime cutoff) {
        var ids = images.findUnreferencedOlderThan(cutoff);
        if (!ids.isEmpty()) {
            images.deleteAllByIdInBatch(ids);
            log.info("Removed {} unused photo(s)", ids.size());
        }
        return ids.size();
    }

    private ImageProcessor.Processed processWithLimit(byte[] raw) {
        boolean acquired = false;
        try {
            acquired = slots.tryAcquire(WAIT_SECONDS, TimeUnit.SECONDS);
            if (!acquired) {
                throw new ConflictException("Photos are being processed right now. Please try again in a moment.");
            }
            return ImageProcessor.process(raw);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ConflictException("The upload was interrupted. Please try again.");
        } finally {
            if (acquired) {
                slots.release();
            }
        }
    }
}
