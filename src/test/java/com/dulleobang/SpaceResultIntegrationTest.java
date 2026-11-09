package com.dulleobang;

import static org.assertj.core.api.Assertions.*;

import com.dulleobang.common.contract.*;
import com.dulleobang.common.contract.controller.ApiFallbackController;
import com.dulleobang.common.crypto.Hashes;
import com.dulleobang.common.json.StrictJson;
import com.dulleobang.user.domain.UserEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.net.*;
import java.net.http.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

class SpaceResultIntegrationTest extends IntegrationTestSupport {
  @Autowired ContractValidator contracts;

  @Autowired
  @Qualifier("requestMappingHandlerMapping")
  RequestMappingHandlerMapping mappings;

  private JsonNode ok(HttpResponse<String> response, int status) {
    assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
    return StrictJson.parse(response.body());
  }

  private URI local(String url) {
    var uri = URI.create(url);
    return URI.create("http://127.0.0.1:" + port + uri.getRawPath() + "?" + uri.getRawQuery());
  }

  private UUID listing() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO listings(listing_id,owner_id,status) VALUES(?,?,'PUBLISHED')", id, owner);
    return id;
  }

  private String user(UUID id) throws Exception {
    users.saveAndFlush(new UserEntity(id, "다른 사용자", Instant.now()));
    UUID sid = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO sessions VALUES(?,?,?,FALSE)",
        sid,
        id,
        java.sql.Timestamp.from(Instant.now().plusSeconds(900)));
    return token(id, sid, false);
  }

  private String capture(UUID target, boolean room, String user) throws Exception {
    var response =
        http.send(
            HttpRequest.newBuilder(
                    URI.create(
                        "http://127.0.0.1:"
                            + port
                            + "/api/"
                            + (room ? "listings/" : "furniture/")
                            + target
                            + "/captures"))
                .header("Authorization", "Bearer " + user)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("Content-Type", "application/zip")
                .POST(
                    HttpRequest.BodyPublishers.ofByteArray(
                        P6CaptureFixtures.zip(room, false, false)))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    return ok(response, 202).path("jobId").asText();
  }

  private String claim(String job, String worker) throws Exception {
    return ok(
            json(
                "POST",
                "/api/v1/internal/ai-jobs/" + job + "/claim",
                Map.of("workerId", "worker"),
                worker),
            200)
        .path("leaseToken")
        .asText();
  }

  private HttpResponse<String> callback(String job, String worker, String lease, Object body)
      throws Exception {
    return json(
        "POST",
        "/api/v1/internal/ai-jobs/" + job + "/complete",
        body,
        worker,
        "X-Job-Lease-Token",
        lease);
  }

  private Map<String, JsonNode> artifacts(
      String job, String worker, String lease, Map<String, byte[]> files) throws Exception {
    var descriptions = new ArrayList<Map<String, Object>>();
    var roles = new ArrayList<>(files.keySet());
    for (String role : roles)
      descriptions.add(
          Map.of(
              "role",
              role,
              "clientFileId",
              UUID.randomUUID(),
              "byteSize",
              files.get(role).length,
              "sha256",
              Hashes.sha256(files.get(role)),
              "contentType",
              role.equals("SCENE")
                  ? "application/octet-stream"
                  : role.equals("GEOMETRY") ? "application/json" : "model/gltf-binary",
              "format",
              role.equals("SCENE")
                  ? "DULLEOBANG_GS_PLY_V1"
                  : role.equals("GEOMETRY") ? "ROOM_GEOMETRY_V1" : "GLB_V2"));
    var grants =
        ok(
                json(
                    "POST",
                    "/api/v1/internal/ai-jobs/" + job + "/artifacts",
                    Map.of("files", descriptions),
                    worker,
                    "X-Job-Lease-Token",
                    lease),
                200)
            .path("uploads");
    var result = new HashMap<String, JsonNode>();
    for (int n = 0; n < roles.size(); n++) {
      var grant = grants.get(n);
      var request =
          HttpRequest.newBuilder(local(grant.path("url").asText()))
              .PUT(HttpRequest.BodyPublishers.ofByteArray(files.get(roles.get(n))));
      grant
          .path("headers")
          .fields()
          .forEachRemaining(e -> request.header(e.getKey(), e.getValue().asText()));
      assertThat(http.send(request.build(), HttpResponse.BodyHandlers.ofString()).statusCode())
          .isEqualTo(200);
      var ref = (ObjectNode) StrictJson.parse(StrictJson.stringify(descriptions.get(n)));
      ref.remove(List.of("role", "clientFileId"));
      ref.set("fileId", grant.path("fileId"));
      result.put(roles.get(n), ref);
    }
    return result;
  }

  private ObjectNode roomResult(String job, String worker, String lease, ObjectNode result)
      throws Exception {
    var b = (ObjectNode) result.path("bundle");
    var raw = new LinkedHashMap<String, byte[]>();
    raw.put("SCENE", P7ResultFixtures.ply());
    raw.put(
        "GEOMETRY",
        StrictJson.stringify(b.path("geometry")).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    raw.put("COLLISION", P7ResultFixtures.glb(P7ResultFixtures.names(b)));
    var refs = artifacts(job, worker, lease, raw);
    b.set("sceneAsset", refs.get("SCENE"));
    b.set("geometryAsset", refs.get("GEOMETRY"));
    b.set("collisionAsset", refs.get("COLLISION"));
    return result;
  }

  @Test
  void checksRouteBindings() throws Exception {
    var actual = new HashSet<String>();
    var controllers = new HashMap<String, String>();
    mappings
        .getHandlerMethods()
        .forEach(
            (info, handler) -> {
              if (handler.getBeanType().equals(ApiFallbackController.class)) return;
              for (String path : info.getPatternValues())
                for (var method : info.getMethodsCondition().getMethods()) {
                  String key = method + " " + path.replaceAll("\\{[^}]+}", "{}");
                  actual.add(key);
                  controllers.put(key, handler.getBeanType().getSimpleName());
                }
            });
    var missing = new ArrayList<String>();
    for (var route : inventory.allRoutes()) {
      String key = route.method() + " " + route.path().replaceAll("\\{[^}]+}", "{}");
      if (!actual.contains(key)) missing.add(key);
    }
    assertThat(missing).isEmpty();
    var expected = new HashSet<String>();
    var routes = new ArrayList<Map<String, Object>>();
    for (var route : inventory.routes()) {
      String key = route.method() + " " + route.path().replaceAll("\\{[^}]+}", "{}");
      expected.add(key);
      routes.add(
          Map.of(
              "method",
              route.method(),
              "path",
              route.path(),
              "audience",
              route.audience(),
              "controller",
              controllers.get(key)));
    }
    var extensions = new ArrayList<Map<String, String>>();
    for (var route : inventory.extensions()) {
      String key = route.method() + " " + route.path();
      expected.add(key);
      extensions.add(
          Map.of(
              "method", route.method(), "path", route.path(), "controller", controllers.get(key)));
    }
    var extra = new HashSet<>(actual);
    extra.removeAll(expected);
    assertThat(extra).isEmpty();
    java.nio.file.Files.writeString(
        java.nio.file.Path.of("verification/spring-route-coverage.json"),
        StrictJson.stringify(
            Map.of(
                "passed",
                true,
                "businessApiCount",
                97,
                "auxiliaryRouteCount",
                4,
                "routes",
                routes,
                "extensionRoutes",
                extensions,
                "evidence",
                "Spring MVC concrete handler registration; workflow success covered by domain"
                    + " integration tests")));
  }

  @Test
  void publishesFurniture() throws Exception {
    String user = activeToken(), worker = token(owner, UUID.randomUUID(), true);
    UUID id =
        UUID.fromString(
            ok(
                    json(
                        "POST",
                        "/api/v1/furniture",
                        Map.of("name", "책상", "category", "DESK"),
                        user,
                        "Idempotency-Key",
                        UUID.randomUUID().toString()),
                    201)
                .path("furnitureId")
                .asText());
    String job = capture(id, false, user), lease = claim(job, worker);
    byte[] glb = P7ResultFixtures.glb(List.of("desk"));
    var refs = artifacts(job, worker, lease, Map.of("MODEL", glb, "COLLISION", glb));
    var result = P7ResultFixtures.example("FurnitureReconstructionResult");
    result.set("modelAsset", refs.get("MODEL"));
    result.set("collisionAsset", refs.get("COLLISION"));
    var wrong = result.deepCopy();
    ((ObjectNode) wrong.path("boundingBox").path("max")).put("y", 2);
    assertThat(callback(job, worker, lease, wrong).statusCode()).isEqualTo(422);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM furniture_models", Integer.class))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM assets WHERE purpose='RESULT' AND verified", Integer.class))
        .isZero();
    var completed = callback(job, worker, lease, result);
    contracts.validate("Job", ok(completed, 200).toString());
    assertThat(StrictJson.parse(callback(job, worker, lease, result).body()))
        .isEqualTo(StrictJson.parse(completed.body()));
    assertThat(callback(job, worker, lease, wrong).statusCode()).isEqualTo(409);
    var canonical = ok(json("GET", "/api/v1/furniture/" + id, null, user), 200);
    contracts.validate("Furniture", canonical.toString());
    assertThat(canonical.path("model").path("modelAsset").path("url").asText())
        .contains("/storage/");
    var mobile = ok(json("GET", "/api/furniture/" + id, null, user), 200);
    assertThat(mobile.path("modelUri").asText()).contains("/storage/");
    assertThat(mobile.path("status").asText()).isEqualTo("COMPLETED");
    assertThat(
            ok(json("GET", "/api/v1/furniture/" + id + "?modelVersion=1", null, user), 200)
                .path("model")
                .path("modelVersion")
                .asInt())
        .isEqualTo(1);
  }

  @Test
  void publishesRoom() throws Exception {
    String ownerToken = activeToken(), worker = token(owner, UUID.randomUUID(), true);
    UUID listing = listing();
    String job = capture(listing, true, ownerToken), lease = claim(job, worker);
    var result =
        roomResult(job, worker, lease, P7ResultFixtures.example("RoomReconstructionResult"));
    ok(callback(job, worker, lease, result), 200);
    UUID tenant = UUID.randomUUID();
    String tenantToken = user(tenant), stranger = user(UUID.randomUUID());
    String prefix = "/api/v1/listings/" + listing;
    var space = ok(json("GET", prefix + "/space", null, tenantToken), 200);
    contracts.validate("Space", space.toString());
    String asset = space.path("bundle").path("sceneAsset").path("fileId").asText();
    ok(json("GET", "/api/v1/assets/" + asset + "/download", null, tenantToken), 200);
    String object = space.path("bundle").path("objects").get(0).path("objectId").asText();
    var patch =
        Map.of(
            "spaceVersion",
            1,
            "objects",
            List.of(Map.of("objectId", object, "ownershipType", "PERSONAL")));
    assertThat(
            json("PATCH", prefix + "/objects", patch, tenantToken, "If-Match", "\"1\"")
                .statusCode())
        .isEqualTo(404);
    ok(json("PATCH", prefix + "/objects", patch, ownerToken, "If-Match", "\"1\""), 200);
    assertThat(
            json("PATCH", prefix + "/objects", patch, ownerToken, "If-Match", "\"1\"").statusCode())
        .isEqualTo(412);
    var stateInput =
        Map.of(
            "sourceSpaceVersion",
            1,
            "sourceObjectsVersion",
            2,
            "removedObjectIds",
            List.of(object));
    String key = UUID.randomUUID().toString();
    var created =
        json("POST", prefix + "/room-states", stateInput, tenantToken, "Idempotency-Key", key);
    var accepted = ok(created, 202);
    assertThat(
            json("POST", prefix + "/room-states", stateInput, tenantToken, "Idempotency-Key", key)
                .body())
        .isEqualTo(created.body());
    String state = accepted.path("roomStateId").asText(),
        removalJob = accepted.path("jobId").asText();
    assertThat(json("GET", "/api/v1/room-states/" + state, null, stranger).statusCode())
        .isEqualTo(404);
    assertThat(json("GET", "/api/v1/room-states/" + state, null, ownerToken).statusCode())
        .isEqualTo(404);
    String removalLease = claim(removalJob, worker);
    var removed = P7ResultFixtures.example("RemovalResult");
    removed.put("roomStateId", state);
    removed = roomResult(removalJob, worker, removalLease, removed);
    ok(callback(removalJob, worker, removalLease, removed), 200);
    var ready = ok(json("GET", "/api/v1/room-states/" + state, null, tenantToken), 200);
    contracts.validate("RoomState", ready.toString());
    assertThat(ready.path("status").asText()).isEqualTo("READY");
    assertThat(ready.path("bundle").path("objects")).isEmpty();
    String privateAsset = ready.path("bundle").path("sceneAsset").path("fileId").asText();
    assertThat(
            json("GET", "/api/v1/assets/" + privateAsset + "/download", null, ownerToken)
                .statusCode())
        .isEqualTo(404);
    assertThat(ok(json("GET", prefix + "/objects", null, ownerToken), 200).path("objects").size())
        .isEqualTo(1);
    assertThat(json("DELETE", "/api/v1/room-states/" + state, null, tenantToken).statusCode())
        .isEqualTo(204);
    assertThat(json("GET", "/api/v1/room-states/" + state, null, tenantToken).statusCode())
        .isEqualTo(404);
  }
}
