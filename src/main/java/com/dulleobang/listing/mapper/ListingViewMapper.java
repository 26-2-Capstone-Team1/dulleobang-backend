package com.dulleobang.listing.mapper;

import com.dulleobang.common.json.StrictJson;
import com.dulleobang.listing.domain.ListingRecord;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class ListingViewMapper {
  public ObjectNode canonical(
      UUID actor,
      ListingRecord listing,
      List<Map<String, Object>> photos,
      Map<String, Object> owner,
      boolean scrapped,
      boolean summary) {
    var value = listing.fields().deepCopy();
    value.set("photos", StrictJson.parse(StrictJson.stringify(photos)));
    value.set("owner", StrictJson.parse(StrictJson.stringify(owner)));
    value.put("isScrapped", scrapped);
    if (summary || !actor.equals(listing.owner()))
      ((ObjectNode) value.path("location")).remove("detailAddress");
    if (summary) {
      value.set(
          "thumbnail",
          photos.isEmpty()
              ? com.fasterxml.jackson.databind.node.NullNode.instance
              : StrictJson.parse(StrictJson.stringify(photos.get(0))));
      value.put("hasSpace", !value.path("currentSpaceVersion").isNull());
      var keys =
          Set.of(
              "listingId",
              "title",
              "price",
              "location",
              "exclusiveAreaM2",
              "thumbnail",
              "hasSpace",
              "status",
              "createdAt",
              "roomType",
              "floor",
              "owner",
              "isScrapped");
      var keep = new ArrayList<String>();
      value.fieldNames().forEachRemaining(keep::add);
      keep.stream().filter(k -> !keys.contains(k)).forEach(value::remove);
    }
    return value;
  }
}
