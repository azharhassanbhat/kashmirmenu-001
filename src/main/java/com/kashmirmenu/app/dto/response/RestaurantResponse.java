package com.kashmirmenu.app.dto.response;


import lombok.*;
import java.time.LocalDateTime;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RestaurantResponse {

    private Long id;

    private String name;

    private String slug;

    private String email;

    private String phone;

    private String address;

    private String city;

    private String state;

    private String country;

    private String logo;

    private String primaryColor;

    private String secondaryColor;

    private String googlePlaceId;

    private Boolean active;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
