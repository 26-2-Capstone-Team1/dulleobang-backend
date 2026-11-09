package com.dulleobang;

import static org.assertj.core.api.Assertions.*;

import com.dulleobang.common.contract.ContractValidator;
import com.dulleobang.common.json.StrictJson;
import com.dulleobang.space.repository.SpaceResultRepository;
import com.dulleobang.user.domain.UserEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

class PlacementIntegrationTest extends IntegrationTestSupport {
  @Autowired ContractValidator contracts;
  @Autowired SpaceResultRepository results;
  UUID listing, furniture, landlord;
  String bearer;

  @BeforeEach
  void fixtures() throws Exception {
    bearer = activeToken();
    landlord = UUID.randomUUID();
    users.saveAndFlush(new UserEntity(landlord, "집주인", Instant.now()));
    listing = listing();
    furniture = furniture(owner);
  }

  UUID listing() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO listings(listing_id,owner_id,status,current_space_version)"
            + " VALUES(?,?,'PUBLISHED',1)",
        id,
        landlord);
    jdbc.update(
        "INSERT INTO spaces(listing_id,space_version,bundle,created_at)"
            + " VALUES(?,1,'{}',CURRENT_TIMESTAMP)",
        id);
    return id;
  }

  UUID furniture(UUID ownerId) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO"
            + " furniture(furniture_id,owner_id,name,category,status,current_model_version,created_at,updated_at)"
            + " VALUES(?,?,'의자','CHAIR','READY',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
        id,
        ownerId);
    jdbc.update("INSERT INTO furniture_models VALUES(?,1,'{\"modelVersion\":1}')", id);
    return id;
  }

  String path() {
    return "/api/v1/listings/" + listing + "/placements";
  }

  ObjectNode body() {
    var value =
        (ObjectNode)
            StrictJson.parse(
                "{\"space\":{\"kind\":\"ORIGINAL\",\"spaceVersion\":1,\"roomStateId\":null},\"placements\":[],\"clientCollisionReport\":null}");
    var item = value.withArray("placements").addObject();
    item.put("placementId", UUID.randomUUID().toString())
        .put("furnitureId", furniture.toString())
        .put("modelVersion", 1);
    var t = item.putObject("transform");
    t.putObject("position").put("x", 1).put("y", 0).put("z", -1);
    t.putObject("rotation").put("x", 0).put("y", 0).put("z", 0).put("w", -0.99995);
    return value;
  }

  void error(HttpResponse<String> response, int status, String code) {
    assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
    assertThat(StrictJson.parse(response.body()).path("code").asText()).isEqualTo(code);
  }

  JsonNode created(ObjectNode value) throws Exception {
    var response = json("PUT", path(), value, bearer, "If-None-Match", "*");
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    contracts.validate("PlacementSet", response.body());
    assertThat(response.headers().firstValue("ETag")).contains("\"1\"");
    return StrictJson.parse(response.body());
  }

  @Test
  void savesPlacementSet() throws Exception {
    var first = created(body());
    assertThat(
            first.path("placements").get(0).path("transform").path("rotation").path("w").asDouble())
        .isEqualTo(1);
    assertThat(first.path("validationAuthority").asText()).isEqualTo("CLIENT_ONLY");
    var empty = body();
    empty.withArray("placements").removeAll();
    var replaced = json("PUT", path(), empty, bearer, "If-Match", "\"1\"");
    assertThat(replaced.statusCode()).as(replaced.body()).isEqualTo(200);
    assertThat(StrictJson.parse(replaced.body()).path("placements")).isEmpty();
    assertThat(replaced.headers().firstValue("ETag")).contains("\"2\"");
    error(json("DELETE", path(), null, bearer, "If-Match", "\"1\""), 412, "VERSION_MISMATCH");
    assertThat(json("DELETE", path(), null, bearer, "If-Match", "\"2\"").statusCode())
        .isEqualTo(204);
    assertThat(request("DELETE", path(), bearer).statusCode()).isEqualTo(204);
    error(request("GET", path(), bearer), 404, "PLACEMENT_NOT_FOUND");
    var second = created(body());
    assertThat(second.path("placementSetId")).isNotEqualTo(first.path("placementSetId"));
  }

  @Test
  void checksPreconditions() throws Exception {
    var value = body();
    error(json("PUT", path(), value, bearer), 428, "PRECONDITION_REQUIRED");
    error(
        json("PUT", path(), value, bearer, "If-None-Match", "*", "If-Match", "\"1\""),
        400,
        "INVALID_PRECONDITION");
    error(json("PUT", path(), value, bearer, "If-None-Match", "bad"), 400, "INVALID_PRECONDITION");
    error(json("PUT", path(), value, bearer, "If-Match", "\"1\""), 412, "PRECONDITION_FAILED");
    created(value);
    error(json("PUT", path(), value, bearer, "If-None-Match", "*"), 412, "PRECONDITION_FAILED");
    error(json("PUT", path(), value, bearer, "If-Match", "\"9\""), 412, "VERSION_MISMATCH");
    assertThat(jdbc.queryForObject("SELECT resource_version FROM placement_sets", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void rejectsInvalidItems() throws Exception {
    var value = body();
    ((ObjectNode) value.path("placements").get(0).path("transform").path("rotation")).put("w", 0);
    error(json("PUT", path(), value, bearer, "If-None-Match", "*"), 422, "INVALID_ROTATION");
    value = body();
    value.withArray("placements").add(value.path("placements").get(0).deepCopy());
    error(json("PUT", path(), value, bearer, "If-None-Match", "*"), 422, "DUPLICATE_ID");
    value = body();
    var report = value.putObject("clientCollisionReport");
    report
        .put("rulesVersion", "1.0")
        .put("evaluatedAt", Instant.now().toString())
        .put("isPlaceable", false);
    report
        .putArray("collisions")
        .addObject()
        .put("placementId", UUID.randomUUID().toString())
        .put("kind", "WALL")
        .putNull("targetId")
        .put("penetrationM", 0.1);
    error(
        json("PUT", path(), value, bearer, "If-None-Match", "*"), 422, "INVALID_COLLISION_REPORT");
    ((ObjectNode) report.path("collisions").get(0))
        .put("placementId", value.path("placements").get(0).path("placementId").asText());
    report.put("evaluatedAt", Instant.now().plusSeconds(120).toString());
    error(
        json("PUT", path(), value, bearer, "If-None-Match", "*"), 422, "INVALID_COLLISION_REPORT");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM placement_sets", Integer.class)).isZero();
    report.put("evaluatedAt", Instant.now().toString());
    var stored = created(value);
    assertThat(stored.path("clientCollisionReport").path("isPlaceable").asBoolean()).isFalse();
  }

  @Test
  void marksStalePlacements() throws Exception {
    var value = body();
    created(value);
    results.model(furniture, 2, StrictJson.parse("{\"modelVersion\":2}"), Instant.now());
    var stale = request("GET", path(), bearer);
    assertThat(stale.statusCode()).isEqualTo(200);
    assertThat(StrictJson.parse(stale.body()).path("isStale").asBoolean()).isTrue();
    assertThat(stale.headers().firstValue("ETag")).contains("\"2\"");
    error(json("PUT", path(), value, bearer, "If-Match", "\"2\""), 409, "STALE_MODEL_VERSION");
    ((ObjectNode) value.path("placements").get(0)).put("modelVersion", 2);
    assertThat(json("PUT", path(), value, bearer, "If-Match", "\"2\"").statusCode()).isEqualTo(200);
    results.space(listing, 2, StrictJson.parse("{}"), Instant.now());
    error(json("PUT", path(), value, bearer, "If-Match", "\"4\""), 409, "STALE_SPACE_VERSION");
    ((ObjectNode) value.path("space")).put("spaceVersion", 2);
    ((ObjectNode) value.path("placements").get(0))
        .put("furnitureId", furniture(landlord).toString());
    error(json("PUT", path(), value, bearer, "If-Match", "\"4\""), 404, "RESOURCE_NOT_FOUND");
  }

  @Test
  void checksStateReference() throws Exception {
    UUID state = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO"
            + " room_states(state_id,owner_id,listing_id,source_space_version,source_objects_version,removed_object_ids,status,source_bundle,created_at)"
            + " VALUES(?,?,?,1,1,'{}','PROCESSING','{}',CURRENT_TIMESTAMP)",
        state,
        owner,
        listing);
    var value = body();
    ((ObjectNode) value.path("space"))
        .put("kind", "ROOM_STATE")
        .put("roomStateId", state.toString());
    error(json("PUT", path(), value, bearer, "If-None-Match", "*"), 409, "ROOM_STATE_NOT_READY");
    jdbc.update(
        "UPDATE room_states SET status='READY',owner_id=? WHERE state_id=?", landlord, state);
    error(json("PUT", path(), value, bearer, "If-None-Match", "*"), 404, "RESOURCE_NOT_FOUND");
    jdbc.update("UPDATE room_states SET owner_id=? WHERE state_id=?", owner, state);
    created(value);
    assertThat(json("DELETE", path(), null, bearer, "If-Match", "\"1\"").statusCode())
        .isEqualTo(204);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM placement_items", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("SELECT room_state_id FROM placement_sets", UUID.class))
        .isNull();
  }

  @Test
  void mapsMobileYaw() throws Exception {
    var value =
        Map.of(
            "placements",
            List.of(
                Map.of(
                    "furnitureId",
                    furniture,
                    "position",
                    Map.of("x", 1, "y", 0, "z", -1),
                    "rotation",
                    Map.of("yaw", 90))));
    String mobile = "/api/listings/" + listing + "/placements";
    var saved = json("PUT", mobile, value, bearer, "If-None-Match", "*");
    assertThat(saved.statusCode()).as(saved.body()).isEqualTo(200);
    contracts.validateMobile("MobilePlacements", saved.body());
    assertThat(
            StrictJson.parse(saved.body())
                .path("placements")
                .get(0)
                .path("rotation")
                .path("yaw")
                .asDouble())
        .isCloseTo(90, within(1e-8));
    var canonical = StrictJson.parse(request("GET", path(), bearer).body());
    contracts.validate("PlacementSet", StrictJson.stringify(canonical));
    assertThat(canonical.path("space").path("kind").asText()).isEqualTo("ORIGINAL");
    assertThat(canonical.path("placements").get(0).path("placementId").asText())
        .isEqualTo(furniture.toString());
    contracts.validateMobile("MobilePlacements", request("GET", mobile, bearer).body());
  }

  @Test
  void rejectsLostUpdates() throws Exception {
    var value = body();
    var start = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    try {
      Callable<Integer> create =
          () -> {
            start.await();
            return json("PUT", path(), value, bearer, "If-None-Match", "*").statusCode();
          };
      var a = pool.submit(create);
      var b = pool.submit(create);
      start.countDown();
      assertThat(List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(200, 412);
      Callable<Integer> update =
          () -> json("PUT", path(), value, bearer, "If-Match", "\"1\"").statusCode();
      a = pool.submit(update);
      b = pool.submit(update);
      assertThat(List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(200, 412);
      assertThat(jdbc.queryForObject("SELECT count(*) FROM placement_sets", Integer.class))
          .isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void filtersArchivedRooms() throws Exception {
    UUID firstListing = listing;
    created(body());
    listing = listing();
    created(body());
    var first = request("GET", "/api/v1/users/me/placements?limit=1", bearer);
    assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
    contracts.validate("PlacementPage", first.body());
    String cursor = StrictJson.parse(first.body()).path("page").path("nextCursor").asText();
    jdbc.update("UPDATE listings SET status='ARCHIVED' WHERE listing_id=?", firstListing);
    var next =
        request(
            "GET",
            "/api/v1/users/me/placements?limit=1&cursor="
                + java.net.URLEncoder.encode(cursor, java.nio.charset.StandardCharsets.UTF_8),
            bearer);
    assertThat(next.statusCode()).isEqualTo(200);
    assertThat(StrictJson.parse(next.body()).path("items")).isEmpty();
    listing = firstListing;
    error(request("GET", path(), bearer), 404, "RESOURCE_NOT_FOUND");
    jdbc.update("UPDATE users SET roles=ARRAY['LANDLORD'] WHERE user_id=?", owner);
    error(request("GET", "/api/v1/users/me/placements", bearer), 403, "FORBIDDEN");
  }
}
