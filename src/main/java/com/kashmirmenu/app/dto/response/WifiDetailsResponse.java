package com.kashmirmenu.app.dto.response;

/** The guest Wi-Fi credentials, returned only by the table-scoped Wi-Fi endpoint. Never cached. */
public record WifiDetailsResponse(String ssid, String security, String password) {
}
