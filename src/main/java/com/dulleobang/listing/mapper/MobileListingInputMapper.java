package com.dulleobang.listing.mapper;

import com.dulleobang.common.exception.ApiException;
import com.dulleobang.common.json.StrictJson;
import com.dulleobang.listing.dto.ListingCreateRequest;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class MobileListingInputMapper {
  public static final Map<String, String> OPTIONS =
      Map.of(
          "parking",
          "PARKING",
          "pet",
          "PET_ALLOWED",
          "elevator",
          "ELEVATOR",
          "aircon",
          "AIR_CONDITIONER",
          "fridge",
          "REFRIGERATOR",
          "washer",
          "WASHING_MACHINE",
          "bed",
          "BED",
          "desk",
          "DESK",
          "closet",
          "WARDROBE",
          "induction",
          "INDUCTION");

  public ObjectNode convert(ListingCreateRequest body) {
    if (body.latitude() == null || body.longitude() == null)
      throw new ApiException(422, "LOCATION_COORDINATES_REQUIRED");
    var value = new LinkedHashMap<String, Object>();
    value.put(
        "location",
        Map.of(
            "roadAddress",
            body.location(),
            "latitude",
            body.latitude(),
            "longitude",
            body.longitude()));
    var price = new LinkedHashMap<String, Object>();
    price.put("leaseType", body.dealType().name());
    price.put("deposit", Math.multiplyExact(body.price().deposit(), 10000));
    price.put("monthlyRent", Math.multiplyExact(body.price().monthly(), 10000));
    price.put(
        "maintenanceFee", body.maintenanceFee() == null ? null : body.maintenanceFee() * 10000);
    price.put("currency", "KRW");
    value.put("price", price);
    value.put("exclusiveAreaM2", body.area());
    value.put("roomType", body.roomType().name());
    value.put("availableFrom", body.availableFrom());
    value.put("rentalOptions", body.rentalOptions().stream().map(OPTIONS::get).toList());
    if (body.floor() != null) value.put("floor", body.floor());
    if (body.totalFloors() != null) value.put("totalFloors", body.totalFloors());
    if (body.supplyArea() != null) value.put("supplyAreaM2", body.supplyArea());
    if (body.rooms() != null) value.put("roomCount", body.rooms());
    if (body.bathrooms() != null) value.put("bathroomCount", body.bathrooms());
    return (ObjectNode) StrictJson.parse(StrictJson.stringify(value));
  }
}
