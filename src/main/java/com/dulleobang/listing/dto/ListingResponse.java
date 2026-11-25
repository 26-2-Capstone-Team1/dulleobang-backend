package com.dulleobang.listing.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record ListingResponse(
    UUID listingId,
    String location,
    Price price,
    BigDecimal maintenanceFee,
    String dealType,
    double latitude,
    double longitude,
    double area,
    List<String> rentalOptions,
    List<String> imageUris,
    String thumbnailUri,
    Agent agent,
    boolean isScrapped,
    String status,
    SpaceInfo space3DInfo,
    String roomType,
    Integer floor,
    @JsonInclude(JsonInclude.Include.NON_NULL) Integer totalFloors,
    @JsonInclude(JsonInclude.Include.NON_NULL) Double supplyArea,
    @JsonInclude(JsonInclude.Include.NON_NULL) Integer rooms,
    @JsonInclude(JsonInclude.Include.NON_NULL) Integer bathrooms,
    @JsonInclude(JsonInclude.Include.NON_NULL) String availableFrom,
    @JsonInclude(JsonInclude.Include.NON_NULL) String livingEnvironment) {
  public record Price(BigDecimal deposit, BigDecimal monthly) {}

  public record Agent(
      UUID userId, String name, String officeName, String phone, boolean isVerifiedBroker) {}

  public record SpaceInfo(String status, UUID jobId) {}
}
