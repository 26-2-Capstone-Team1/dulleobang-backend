package com.dulleobang.listing.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import org.hibernate.validator.constraints.CodePointLength;

public record ListingCreateRequest(
    @NotBlank @CodePointLength(max = 200) String location,
    @NotNull DealType dealType,
    @NotNull @Valid Price price,
    @Min(0) @Max(100_000_000) Long maintenanceFee,
    @NotNull RoomType roomType,
    JsonNode floor,
    @Min(1) @Max(200) Integer totalFloors,
    @NotNull @DecimalMin("0.1") @DecimalMax("10000") Double area,
    @DecimalMin("0.1") @DecimalMax("10000") Double supplyArea,
    @Min(1) @Max(50) Integer rooms,
    @Min(0) @Max(30) Integer bathrooms,
    @NotBlank String availableFrom,
    @NotNull List<String> rentalOptions,
    @DecimalMin("-90") @DecimalMax("90") Double latitude,
    @DecimalMin("-180") @DecimalMax("180") Double longitude) {
  public enum DealType {
    MONTHLY,
    JEONSE
  }

  public enum RoomType {
    ONE_ROOM,
    TWO_ROOM,
    THREE_ROOM,
    OFFICETEL
  }

  public record Price(
      @NotNull @Min(0) @Max(100_000_000) Long deposit,
      @NotNull @Min(0) @Max(100_000_000) Long monthly) {}
}
