package com.kashmirmenu.app.controller;

import com.kashmirmenu.app.service.qr.QrRules;
import com.kashmirmenu.app.dto.request.CreateRestaurantTableRequest;
import com.kashmirmenu.app.dto.request.UpdateRestaurantTableRequest;
import com.kashmirmenu.app.dto.response.RestaurantTableResponse;
import com.kashmirmenu.app.service.RestaurantTableService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.MediaType;


import java.util.List;

@RestController
@RequestMapping("/api/v1/tables")
@PreAuthorize("hasAnyRole('ADMIN','RESTAURANT_OWNER','MANAGER')")
@RequiredArgsConstructor
public class RestaurantTableController {

    private final RestaurantTableService restaurantTableService;


    @PostMapping
    public ResponseEntity<RestaurantTableResponse> createTable(
            @Valid @RequestBody CreateRestaurantTableRequest request) {

        RestaurantTableResponse response =
                restaurantTableService.createTable(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }

    @GetMapping
    public ResponseEntity<List<RestaurantTableResponse>> getAllTables() {

        return ResponseEntity.ok(
                restaurantTableService.getAllTables());
    }

    @GetMapping("/{id}")
    public ResponseEntity<RestaurantTableResponse> getTable(
            @PathVariable Long id) {

        return ResponseEntity.ok(
                restaurantTableService.getTable(id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<RestaurantTableResponse> updateTable(
            @PathVariable Long id,
            @Valid @RequestBody UpdateRestaurantTableRequest request) {

        return ResponseEntity.ok(
                restaurantTableService.updateTable(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTable(
            @PathVariable Long id) {

        restaurantTableService.deleteTable(id);

        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/activate")
    public ResponseEntity<Void> activateTable(
            @PathVariable Long id) {

        restaurantTableService.activateTable(id);

        return ResponseEntity.ok().build();
    }

    @PatchMapping("/{id}/deactivate")
    public ResponseEntity<Void> deactivateTable(
            @PathVariable Long id) {

        restaurantTableService.deactivateTable(id);

        return ResponseEntity.ok().build();
    }

    /**
     * The table's QR code as a PNG. {@code size} (pixels, optional) asks for a sharper image for printing big; it
     * is held to 200 to 1200 and an unreadable value means the default, so it never causes an error.
     */
    @GetMapping(value = "/{id}/qr", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> getQrCode(
            @PathVariable Long id,
            @RequestParam(required = false) String size) {

        return ResponseEntity.ok(
                restaurantTableService.getTableQrCode(id, QrRules.size(size)));
    }

    /** What the printable-cards page needs: the restaurant's name and its active tables in natural order. */
    @GetMapping("/qr-cards")
    public ResponseEntity<com.kashmirmenu.app.dto.response.QrCardsResponse> getQrCards() {

        return ResponseEntity.ok(restaurantTableService.getQrCards());
    }
}