package com.dulleobang.listing.repository;

import com.dulleobang.common.json.*;
import com.dulleobang.listing.domain.ListingRecord;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;

@Repository
public class ListingRepository {
  private static final Map<String, String> FIELDS =
      Map.ofEntries(
          Map.entry("title", "title"),
          Map.entry("description", "description"),
          Map.entry("exclusiveAreaM2", "exclusive_area"),
          Map.entry("floor", "floor"),
          Map.entry("totalFloors", "total_floors"),
          Map.entry("roomType", "room_type"),
          Map.entry("supplyAreaM2", "supply_area"),
          Map.entry("roomCount", "room_count"),
          Map.entry("bathroomCount", "bathroom_count"),
          Map.entry("availableFrom", "available_from"),
          Map.entry("livingEnvironment", "living_environment"));
  private static final Map<String, String> LOCATION =
      Map.ofEntries(
          Map.entry("roadAddress", "road_address"),
          Map.entry("detailAddress", "detail_address"),
          Map.entry("regionCode", "region_code"),
          Map.entry("latitude", "latitude"),
          Map.entry("longitude", "longitude"));
  private static final Map<String, String> PRICE =
      Map.ofEntries(
          Map.entry("leaseType", "lease_type"),
          Map.entry("deposit", "deposit"),
          Map.entry("monthlyRent", "monthly_rent"),
          Map.entry("maintenanceFee", "maintenance_fee"));
  private final JdbcTemplate jdbc;
  private final NamedParameterJdbcTemplate named;

  public ListingRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
    this.named = new NamedParameterJdbcTemplate(jdbc);
  }

  private ObjectNode object(Map<String, Object> value) {
    return (ObjectNode) StrictJson.parse(StrictJson.stringify(value));
  }

  private final RowMapper<ListingRecord> mapper =
      (rs, n) -> {
        var fields = new LinkedHashMap<String, Object>();
        for (var entry : FIELDS.entrySet()) {
          Object value = rs.getObject(entry.getValue());
          if (value != null || entry.getKey().equals("floor")) fields.put(entry.getKey(), value);
        }
        var location = new LinkedHashMap<String, Object>();
        for (var entry : LOCATION.entrySet()) {
          Object value = rs.getObject(entry.getValue());
          if (value != null) location.put(entry.getKey(), value);
        }
        var price = new LinkedHashMap<String, Object>();
        for (var entry : PRICE.entrySet())
          price.put(entry.getKey(), rs.getObject(entry.getValue()));
        price.put("currency", "KRW");
        fields.put("location", location);
        fields.put("price", price);
        fields.put("rentalOptions", List.of((String[]) rs.getArray("rental_options").getArray()));
        UUID id = rs.getObject("listing_id", UUID.class),
            owner = rs.getObject("owner_id", UUID.class);
        String status = rs.getString("status");
        long version = rs.getLong("resource_version");
        fields.put("listingId", id);
        fields.put("ownerId", owner);
        fields.put("status", status);
        fields.put("version", version);
        fields.put("currentSpaceVersion", rs.getObject("current_space_version"));
        fields.put("activeJobId", rs.getObject("active_job_id"));
        fields.put("createdAt", ApiTime.format(rs.getTimestamp("created_at").toInstant()));
        fields.put("updatedAt", ApiTime.format(rs.getTimestamp("updated_at").toInstant()));
        return new ListingRecord(
            id, owner, status, version, rs.getTimestamp("deleted_at") != null, object(fields));
      };

  public Optional<ListingRecord> find(UUID id, boolean lock) {
    return jdbc
        .query(
            "SELECT * FROM listings WHERE listing_id=?" + (lock ? " FOR UPDATE" : ""), mapper, id)
        .stream()
        .findFirst();
  }

  public List<ListingRecord> all() {
    return jdbc.query(
        "SELECT * FROM listings WHERE deleted_at IS NULL ORDER BY created_at DESC,listing_id",
        mapper);
  }

  public void create(UUID id, UUID owner, JsonNode fields, Instant now) {
    jdbc.update(
        "INSERT INTO listings(listing_id,owner_id,created_at,updated_at) VALUES(?,?,?,?)",
        id,
        owner,
        java.sql.Timestamp.from(now),
        java.sql.Timestamp.from(now));
    write(id, fields, now, false);
  }

  public void write(UUID id, JsonNode fields, Instant now, boolean bump) {
    var params = new MapSqlParameterSource("id", id).addValue("now", java.sql.Timestamp.from(now));
    var assignments = new ArrayList<String>();
    bind(params, assignments, FIELDS, fields);
    bind(params, assignments, LOCATION, fields.path("location"));
    bind(params, assignments, PRICE, fields.path("price"));
    if (fields.has("rentalOptions")) {
      params.addValue("options", StrictJson.stringify(fields.path("rentalOptions")));
      assignments.add(
          "rental_options=ARRAY(SELECT jsonb_array_elements_text(CAST(:options AS jsonb)))");
    }
    assignments.add("updated_at=:now");
    if (bump) assignments.add("resource_version=resource_version+1");
    named.update(
        "UPDATE listings SET " + String.join(",", assignments) + " WHERE listing_id=:id", params);
  }

  private void bind(
      MapSqlParameterSource params,
      List<String> assignments,
      Map<String, String> mapping,
      JsonNode input) {
    for (var entry : mapping.entrySet())
      if (input.has(entry.getKey())) {
        var node = input.get(entry.getKey());
        Object value =
            node.isNull()
                ? null
                : node.isIntegralNumber()
                    ? node.longValue()
                    : node.isNumber() ? node.doubleValue() : node.asText();
        params.addValue(entry.getValue(), value);
        assignments.add(entry.getValue() + "=:" + entry.getValue());
      }
  }

  public void publication(UUID id, String status, Instant now) {
    jdbc.update(
        "UPDATE listings SET status=?,updated_at=?,resource_version=resource_version+1 WHERE"
            + " listing_id=?",
        status,
        java.sql.Timestamp.from(now),
        id);
  }

  public void delete(UUID id, Instant now) {
    jdbc.update(
        "UPDATE listings SET deleted_at=? WHERE listing_id=?", java.sql.Timestamp.from(now), id);
  }

  public List<UUID> photoIds(UUID id) {
    return jdbc.query(
        "SELECT file_id FROM listing_photos WHERE listing_id=? ORDER BY ordinal",
        (rs, n) -> rs.getObject(1, UUID.class),
        id);
  }

  public void replacePhotos(UUID id, List<UUID> sources, List<UUID> derived) {
    jdbc.update("DELETE FROM listing_photos WHERE listing_id=?", id);
    for (int n = 0; n < sources.size(); n++)
      jdbc.update(
          "INSERT INTO listing_photos VALUES(?,?,?,?)", id, n, sources.get(n), derived.get(n));
  }

  public boolean scrapped(UUID actor, UUID id) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM scraps WHERE user_id=? AND listing_id=?)",
            Boolean.class,
            actor,
            id));
  }
}
