package com.kashmirmenu.app.dto.response;

import java.util.List;

/**
 * What is needed to lay out a sheet of printable table cards: the restaurant's name and its ACTIVE tables in
 * the order a person expects ("Table 2" before "Table 10"). The QR image itself is fetched per table.
 */
public record QrCardsResponse(String restaurantName, List<Card> tables) {

    public record Card(Long id, String tableName, String tableCode) {
    }
}
