package com.dulleobang.listing.mapper;

import com.dulleobang.listing.dto.ListingResponse;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.StreamSupport;
import org.springframework.stereotype.Component;

@Component
public class MobileListingMapper {
  private static final Map<String, String> APP_OPTIONS = new HashMap<>();

  static {
    MobileListingInputMapper.OPTIONS.forEach((app, stored) -> APP_OPTIONS.put(stored, app));
  }

  public ListingResponse convert(JsonNode value) {
    var location = value.path("location");
    var price = value.path("price");
    var owner = value.path("owner");
    var options =
        StreamSupport.stream(value.path("rentalOptions").spliterator(), false)
            .map(JsonNode::asText)
            .map(option -> APP_OPTIONS.getOrDefault(option, option.toLowerCase(Locale.ROOT)))
            .toList();
    var photos =
        StreamSupport.stream(value.path("photos").spliterator(), false)
            .map(photo -> photo.path("url").asText())
            .toList();
    return new ListingResponse(
        uuid(value, "listingId"),
        location.path("roadAddress").asText(),
        new ListingResponse.Price(money(price, "deposit"), money(price, "monthlyRent")),
        money(price, "maintenanceFee"),
        price.path("leaseType").asText(),
        location.path("latitude").doubleValue(),
        location.path("longitude").doubleValue(),
        value.path("exclusiveAreaM2").doubleValue(),
        options,
        photos,
        photos.isEmpty() ? null : photos.getFirst(),
        new ListingResponse.Agent(
            uuid(owner, "userId"),
            text(owner, "displayName"),
            text(owner, "officeName"),
            text(owner, "phone"),
            owner.path("isVerifiedListingAgent").asBoolean()),
        value.path("isScrapped").asBoolean(),
        value.path("status").asText(),
        new ListingResponse.SpaceInfo(
            value.path("currentSpaceVersion").isNull() ? "NONE" : "COMPLETED",
            uuid(value, "activeJobId")),
        text(value, "roomType"),
        integer(value, "floor"),
        integer(value, "totalFloors"),
        value.hasNonNull("supplyAreaM2") ? value.get("supplyAreaM2").doubleValue() : null,
        integer(value, "roomCount"),
        integer(value, "bathroomCount"),
        text(value, "availableFrom"),
        text(value, "livingEnvironment"),
        text(value, "title"),
        text(value, "description"),
        text(location, "detailAddress"),
        text(location, "regionCode"));
  }

  private BigDecimal money(JsonNode value, String field) {
    return value.hasNonNull(field)
        ? value.get(field).decimalValue().divide(BigDecimal.valueOf(10000))
        : null;
  }

  private Integer integer(JsonNode value, String field) {
    return value.hasNonNull(field) ? value.get(field).intValue() : null;
  }

  private String text(JsonNode value, String field) {
    return value.path(field).asText(null);
  }

  private UUID uuid(JsonNode value, String field) {
    return value.hasNonNull(field) ? UUID.fromString(value.get(field).asText()) : null;
  }
}
