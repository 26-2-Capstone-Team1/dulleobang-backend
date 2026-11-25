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

  public ObjectNode patch(ObjectNode body, ObjectNode previous) {
    var result = body.deepCopy();
    patchLocation(previous, result);
    patchPrice(previous, result);
    for (var field :
        Map.of(
                "area",
                "exclusiveAreaM2",
                "supplyArea",
                "supplyAreaM2",
                "rooms",
                "roomCount",
                "bathrooms",
                "bathroomCount")
            .entrySet()) {
      var value = result.remove(field.getKey());
      if (value != null) result.set(field.getValue(), value);
    }
    if (body.has("rentalOptions")) {
      var options = result.putArray("rentalOptions");
      body.get("rentalOptions")
          .forEach(
              option -> {
                String value = option.asText();
                options.add(OPTIONS.getOrDefault(value, value.toUpperCase(Locale.ROOT)));
              });
    }
    return result;
  }

  private void patchLocation(ObjectNode previous, ObjectNode result) {
    var location = ((ObjectNode) previous.get("location")).deepCopy();
    boolean changed = false;
    for (var field :
        Map.of(
                "location",
                "roadAddress",
                "detailAddress",
                "detailAddress",
                "regionCode",
                "regionCode",
                "latitude",
                "latitude",
                "longitude",
                "longitude")
            .entrySet()) {
      var value = result.remove(field.getKey());
      if (value != null) {
        location.set(field.getValue(), value);
        changed = true;
      }
    }
    if (changed) result.set("location", location);
  }

  private void patchPrice(ObjectNode previous, ObjectNode result) {
    var price = ((ObjectNode) previous.get("price")).deepCopy();
    var amount = result.remove("price");
    var deal = result.remove("dealType");
    var maintenance = result.remove("maintenanceFee");
    if (amount == null && deal == null && maintenance == null) return;
    if (amount != null) {
      price.put("deposit", Math.multiplyExact(amount.get("deposit").longValue(), 10_000));
      price.put("monthlyRent", Math.multiplyExact(amount.get("monthly").longValue(), 10_000));
    }
    if (deal != null) price.set("leaseType", deal);
    if (maintenance != null) {
      if (maintenance.isNull()) price.putNull("maintenanceFee");
      else price.put("maintenanceFee", Math.multiplyExact(maintenance.longValue(), 10_000));
    }
    result.set("price", price);
  }
}
