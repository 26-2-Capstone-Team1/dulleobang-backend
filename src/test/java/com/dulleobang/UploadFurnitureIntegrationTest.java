package com.dulleobang;

import static org.assertj.core.api.Assertions.*;

import com.dulleobang.common.contract.ContractValidator;
import com.dulleobang.common.crypto.Hashes;
import com.dulleobang.common.json.StrictJson;
import com.dulleobang.upload.validation.ImageValidator;
import com.fasterxml.jackson.databind.JsonNode;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class UploadFurnitureIntegrationTest extends IntegrationTestSupport {
  @org.junit.jupiter.api.Test
  void rejectsInvalidQueries() throws Exception {
    String token = activeToken();
    for (String query : List.of("unknown=1", "limit=1&limit=2", "limit=0", "status=UNKNOWN"))
      assertThat(request("GET", "/api/users/me/furniture?" + query, token).statusCode())
          .as(query)
          .isEqualTo(400);
    assertThat(
            request("GET", "/api/v1/furniture/" + UUID.randomUUID() + "?modelVersion=0", token)
                .statusCode())
        .isEqualTo(400);
  }

  @Autowired ContractValidator contracts;
  @Autowired ImageValidator images;

  byte[] photo() throws Exception {
    var image = new BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB);
    try (var out = new ByteArrayOutputStream()) {
      ImageIO.write(image, "JPEG", out);
      return out.toByteArray();
    }
  }

  JsonNode upload(String purpose, UUID target, byte[] bytes, String token) throws Exception {
    String type = purpose.equals("LISTING_AGENT_DOCUMENT") ? "USER" : "LISTING";
    var body =
        Map.of(
            "purpose",
            purpose,
            "targetType",
            type,
            "targetId",
            target,
            "files",
            List.of(
                Map.of(
                    "clientFileId",
                    UUID.randomUUID(),
                    "fileName",
                    "photo.jpg",
                    "contentType",
                    "image/jpeg",
                    "byteSize",
                    bytes.length,
                    "sha256",
                    Hashes.sha256(bytes))));
    var response =
        json(
            "POST",
            "/api/v1/upload-sessions",
            body,
            token,
            "Idempotency-Key",
            UUID.randomUUID().toString());
    assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
    return StrictJson.parse(response.body());
  }

  URI local(String grant) {
    var uri = URI.create(grant);
    return URI.create("http://127.0.0.1:" + port + uri.getRawPath() + "?" + uri.getRawQuery());
  }

  HttpResponse<byte[]> put(JsonNode grant, byte[] bytes, String method, String checksum)
      throws Exception {
    var request =
        HttpRequest.newBuilder(local(grant.path("url").asText()))
            .header("Content-Type", "image/jpeg")
            .header(
                "x-amz-checksum-sha256",
                checksum == null
                    ? grant.path("headers").path("x-amz-checksum-sha256").asText()
                    : checksum)
            .method(method, HttpRequest.BodyPublishers.ofByteArray(bytes))
            .build();
    return http.send(request, HttpResponse.BodyHandlers.ofByteArray());
  }

  @Test
  void mapsFurnitureTypes() throws Exception {
    String token = activeToken();
    Map<String, String> categories =
        Map.of(
            "bed",
            "BED",
            "desk",
            "DESK",
            "sofa",
            "SOFA",
            "closet",
            "WARDROBE",
            "storage",
            "SHELF",
            "chair",
            "CHAIR",
            "other",
            "OTHER");
    for (var entry : categories.entrySet()) {
      var body = Map.of("name", "가구", "category", entry.getKey());
      String key = UUID.randomUUID().toString();
      var created = json("POST", "/api/furniture", body, token, "Idempotency-Key", key);
      assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
      contracts.validateMobile("MobileFurniture", created.body());
      assertThat(json("POST", "/api/furniture", body, token, "Idempotency-Key", key).body())
          .isEqualTo(created.body());
      String id = StrictJson.parse(created.body()).path("furnitureId").asText();
      var v1 = json("GET", "/api/v1/furniture/" + id, null, token);
      contracts.validate("Furniture", v1.body());
      assertThat(StrictJson.parse(v1.body()).path("category").asText()).isEqualTo(entry.getValue());
      var changed =
          json(
              "PATCH", "/api/v1/furniture/" + id, Map.of("name", "수정"), token, "If-Match", "\"1\"");
      assertThat(changed.statusCode()).isEqualTo(200);
      assertThat(
              json("DELETE", "/api/v1/furniture/" + id, null, token, "If-Match", "\"1\"")
                  .statusCode())
          .isEqualTo(412);
      assertThat(
              json("DELETE", "/api/v1/furniture/" + id, null, token, "If-Match", "\"2\"")
                  .statusCode())
          .isEqualTo(204);
      assertThat(json("GET", "/api/furniture/" + id, null, token).statusCode()).isEqualTo(404);
    }
    assertThat(
            json(
                    "POST",
                    "/api/v1/furniture",
                    Map.of("name", "bad", "category", "closet"),
                    token,
                    "Idempotency-Key",
                    UUID.randomUUID().toString())
                .statusCode())
        .isEqualTo(422);
  }

  @Test
  void checksFurnitureLists() throws Exception {
    String token = activeToken();
    for (int n = 0; n < 3; n++) {
      var result =
          json(
              "POST",
              "/api/v1/furniture",
              Map.of(
                  "name",
                  "Desk " + n,
                  "category",
                  "DESK",
                  "measuredDimensions",
                  Map.of("width", 1.2, "height", .8, "depth", .6)),
              token,
              "Idempotency-Key",
              UUID.randomUUID().toString());
      assertThat(result.statusCode()).as(result.body()).isEqualTo(201);
      contracts.validate("Furniture", result.body());
      var get =
          json(
              "GET",
              "/api/v1/furniture/" + StrictJson.parse(result.body()).path("furnitureId").asText(),
              null,
              token);
      contracts.validate("Furniture", get.body());
    }
    var first = json("GET", "/api/v1/users/me/furniture?limit=1", null, token);
    assertThat(first.statusCode()).isEqualTo(200);
    contracts.validate("FurniturePage", first.body());
    String cursor = StrictJson.parse(first.body()).path("page").path("nextCursor").asText();
    var next = json("GET", "/api/v1/users/me/furniture?limit=1&cursor=" + cursor, null, token);
    assertThat(next.statusCode()).isEqualTo(200);
    assertThat(StrictJson.parse(first.body()).path("items").get(0).path("furnitureId"))
        .isNotEqualTo(StrictJson.parse(next.body()).path("items").get(0).path("furnitureId"));
    var all = json("GET", "/api/users/me/furniture", null, token);
    assertThat(StrictJson.parse(all.body()).path("items")).hasSize(3);
    assertThat(StrictJson.parse(all.body()).path("nextCursor").isNull()).isTrue();
  }

  @Test
  void checksSignedStorage() throws Exception {
    String token = activeToken();
    byte[] bytes = photo();
    var created = upload("LISTING_AGENT_DOCUMENT", owner, bytes, token);
    String id = created.path("session").path("uploadSessionId").asText();
    var grant = created.path("uploads").get(0);
    assertThat(put(grant, bytes, "GET", null).statusCode()).isEqualTo(403);
    assertThat(put(grant, bytes, "PUT", "bad").statusCode()).isEqualTo(422);
    byte[] corrupt = bytes.clone();
    corrupt[20] ^= 1;
    assertThat(put(grant, corrupt, "PUT", null).statusCode()).isEqualTo(422);
    var uploaded = put(grant, bytes, "PUT", null);
    assertThat(uploaded.statusCode()).isEqualTo(200);
    var verified = json("POST", "/api/v1/upload-sessions/" + id + "/complete", null, token);
    assertThat(verified.statusCode()).as(verified.body()).isEqualTo(200);
    contracts.validate("UploadSession", verified.body());
    assertThat(put(grant, bytes, "PUT", null).statusCode()).isEqualTo(409);
    var downloaded =
        json("GET", "/api/v1/assets/" + grant.path("fileId").asText() + "/download", null, token);
    assertThat(downloaded.statusCode()).isEqualTo(200);
    var actual =
        http.send(
            HttpRequest.newBuilder(local(StrictJson.parse(downloaded.body()).path("url").asText()))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofByteArray());
    assertThat(actual.statusCode()).isEqualTo(200);
    assertThat(actual.body()).isEqualTo(bytes);
    UUID other = UUID.randomUUID();
    users.saveAndFlush(
        new com.dulleobang.user.domain.UserEntity(other, "other", java.time.Instant.now()));
    UUID sid = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO sessions VALUES(?,?,CURRENT_TIMESTAMP+INTERVAL '1 hour',FALSE)", sid, other);
    String otherToken = token(other, sid, false);
    assertThat(
            json(
                    "GET",
                    "/api/v1/assets/" + grant.path("fileId").asText() + "/download",
                    null,
                    otherToken)
                .statusCode())
        .isEqualTo(404);
    assertThat(json("GET", "/api/v1/upload-sessions/" + id, null, otherToken).statusCode())
        .isEqualTo(404);
  }

  @Test
  void checksUploadState() throws Exception {
    String token = activeToken();
    byte[] bytes = photo();
    var created = upload("LISTING_AGENT_DOCUMENT", owner, bytes, token);
    String id = created.path("session").path("uploadSessionId").asText();
    var grant = created.path("uploads").get(0);
    assertThat(
            json("POST", "/api/v1/upload-sessions/" + id + "/complete", null, token).statusCode())
        .isEqualTo(409);
    var renewed =
        json(
            "POST",
            "/api/v1/upload-sessions/" + id + "/upload-urls",
            Map.of("clientFileIds", List.of(grant.path("clientFileId").asText())),
            token);
    assertThat(renewed.statusCode()).isEqualTo(200);
    jdbc.update(
        "UPDATE upload_sessions SET expires_at=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE"
            + " session_id=?",
        UUID.fromString(id));
    assertThat(put(grant, bytes, "PUT", null).statusCode()).isEqualTo(410);
    assertThat(
            json("POST", "/api/v1/upload-sessions/" + id + "/complete", null, token).statusCode())
        .isEqualTo(410);
    var expired = json("GET", "/api/v1/upload-sessions/" + id, null, token);
    assertThat(StrictJson.parse(expired.body()).path("status").asText()).isEqualTo("EXPIRED");
    byte[] invalid = "not an image".getBytes();
    created = upload("LISTING_AGENT_DOCUMENT", owner, invalid, token);
    grant = created.path("uploads").get(0);
    assertThat(put(grant, invalid, "PUT", null).statusCode()).isEqualTo(200);
    assertThat(
            json(
                    "POST",
                    "/api/v1/upload-sessions/"
                        + created.path("session").path("uploadSessionId").asText()
                        + "/complete",
                    null,
                    token)
                .statusCode())
        .isEqualTo(422);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM assets WHERE verified", Integer.class))
        .isZero();
  }

  @Test
  void sanitizesPhotos() throws Exception {
    String token = activeToken();
    UUID listing = UUID.randomUUID();
    jdbc.update("INSERT INTO listings(listing_id,owner_id) VALUES(?,?)", listing, owner);
    byte[] bytes = photo();
    var created = upload("LISTING_PHOTO", listing, bytes, token);
    var grant = created.path("uploads").get(0);
    put(grant, bytes, "PUT", null);
    var completed =
        json(
            "POST",
            "/api/v1/upload-sessions/"
                + created.path("session").path("uploadSessionId").asText()
                + "/complete",
            null,
            token);
    assertThat(completed.statusCode()).as(completed.body()).isEqualTo(200);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM assets WHERE purpose='PUBLIC_PHOTO' AND verified AND stored",
                Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT derivative_id FROM assets WHERE file_id=?",
                UUID.class,
                UUID.fromString(grant.path("fileId").asText())))
        .isNotNull();
  }
}
