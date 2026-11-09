package com.dulleobang;

import static org.assertj.core.api.Assertions.*;

import com.dulleobang.admin.service.AdminReviewService;
import com.dulleobang.common.contract.ContractValidator;
import com.dulleobang.common.json.StrictJson;
import com.fasterxml.jackson.databind.JsonNode;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ProductIntegrationTest extends IntegrationTestSupport {
  @Autowired ContractValidator contracts;
  @Autowired AdminReviewService admin;

  Map<String, Object> mobileListing() {
    return Map.of(
        "location",
        "서울 동작구 상도로 10",
        "dealType",
        "MONTHLY",
        "price",
        Map.of("deposit", 500, "monthly", 31),
        "maintenanceFee",
        5,
        "roomType",
        "ONE_ROOM",
        "area",
        23.8,
        "availableFrom",
        "IMMEDIATE",
        "rentalOptions",
        List.of("aircon"),
        "latitude",
        37.5,
        "longitude",
        126.9);
  }

  JsonNode create(String token) throws Exception {
    var response =
        json(
            "POST",
            "/api/listings",
            mobileListing(),
            token,
            "Idempotency-Key",
            UUID.randomUUID().toString());
    assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
    contracts.validateMobile("MobileListing", response.body());
    return StrictJson.parse(response.body());
  }

  String tenant() throws Exception {
    UUID user = UUID.randomUUID(), session = UUID.randomUUID();
    users.saveAndFlush(
        new com.dulleobang.user.domain.UserEntity(user, "다른 사용자", java.time.Instant.now()));
    jdbc.update(
        "INSERT INTO sessions VALUES(?,?,CURRENT_TIMESTAMP+INTERVAL '1 hour',FALSE)",
        session,
        user);
    return token(user, session, false);
  }

  HttpResponse<String> multipart(
      String path, String part, String token, String key, Map<String, String> fields)
      throws Exception {
    String boundary = "test-boundary-" + UUID.randomUUID();
    var out = new ByteArrayOutputStream();
    for (var field : fields.entrySet())
      out.write(
          ("--"
                  + boundary
                  + "\r\nContent-Disposition: form-data; name=\""
                  + field.getKey()
                  + "\"\r\n\r\n"
                  + field.getValue()
                  + "\r\n")
              .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    out.write(
        ("--"
                + boundary
                + "\r\nContent-Disposition: form-data; name=\""
                + part
                + "\"; filename=\"photo.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n")
            .getBytes());
    var image = new BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB);
    ImageIO.write(image, "JPEG", out);
    out.write(("\r\n--" + boundary + "--\r\n").getBytes());
    return http.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .header("Authorization", "Bearer " + token)
            .header("Idempotency-Key", key)
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  JsonNode publishPhotos(String token, String id) throws Exception {
    var uploaded =
        multipart(
            "/api/listings/" + id + "/images",
            "images",
            token,
            UUID.randomUUID().toString(),
            Map.of());
    assertThat(uploaded.statusCode()).as(uploaded.body()).isEqualTo(200);
    contracts.validateMobile("MobileListing", uploaded.body());
    return StrictJson.parse(uploaded.body());
  }

  @Test
  void mapsMobileListing() throws Exception {
    String ownerToken = activeToken(), other = tenant();
    var created = create(ownerToken);
    String id = created.path("listingId").asText();
    var canonical = json("GET", "/api/v1/listings/" + id, null, ownerToken);
    assertThat(canonical.statusCode()).isEqualTo(200);
    contracts.validate("Listing", canonical.body());
    var value = StrictJson.parse(canonical.body());
    assertThat(value.path("price").path("deposit").asLong()).isEqualTo(5000000);
    assertThat(value.path("price").path("monthlyRent").asLong()).isEqualTo(310000);
    assertThat(value.path("price").path("maintenanceFee").asLong()).isEqualTo(50000);
    assertThat(json("GET", "/api/listings/" + id, null, other).statusCode()).isEqualTo(404);
    var location =
        (com.fasterxml.jackson.databind.node.ObjectNode) value.path("location").deepCopy();
    location.put("detailAddress", "비공개 101호");
    var patched =
        json(
            "PATCH",
            "/api/v1/listings/" + id,
            Map.of("location", location),
            ownerToken,
            "If-Match",
            "\"1\"");
    assertThat(patched.statusCode()).as(patched.body()).isEqualTo(200);
    assertThat(
            json(
                    "PUT",
                    "/api/v1/listings/" + id + "/publication",
                    Map.of("status", "PUBLISHED"),
                    ownerToken,
                    "If-Match",
                    "\"2\"")
                .statusCode())
        .isEqualTo(409);
    var published = publishPhotos(ownerToken, id);
    assertThat(published.path("status").asText()).isEqualTo("PUBLISHED");
    assertThat(published.path("imageUris")).hasSize(1);
    var publicGet = json("GET", "/api/v1/listings/" + id, null, other);
    contracts.validate("Listing", publicGet.body());
    assertThat(StrictJson.parse(publicGet.body()).path("location").has("detailAddress")).isFalse();
    assertThat(StrictJson.parse(publicGet.body()).path("owner").path("phone").isNull()).isTrue();
    var search =
        json(
            "GET", "/api/v1/listings?q=%EC%83%81%EB%8F%84&minDeposit=5000000&limit=1", null, other);
    assertThat(search.statusCode()).as(search.body()).isEqualTo(200);
    contracts.validate("ListingPage", search.body());
    assertThat(StrictJson.parse(search.body()).path("items")).hasSize(1);
    assertThat(
            StrictJson.parse(search.body())
                .path("items")
                .get(0)
                .path("location")
                .has("detailAddress"))
        .isFalse();
    var invalid = new LinkedHashMap<>(mobileListing());
    invalid.remove("latitude");
    assertThat(
            json(
                    "POST",
                    "/api/listings",
                    invalid,
                    ownerToken,
                    "Idempotency-Key",
                    UUID.randomUUID().toString())
                .statusCode())
        .isEqualTo(422);
  }

  @Test
  void checksUploadErrors() throws Exception {
    String token = activeToken(), id = create(token).path("listingId").asText();
    for (String suffix : List.of("/images", "/captures"))
      assertThat(
              json(
                      "POST",
                      "/api/listings/" + id + suffix,
                      Map.of(),
                      token,
                      "Idempotency-Key",
                      UUID.randomUUID().toString())
                  .statusCode())
          .isEqualTo(415);
    var request =
        HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/api/listings/" + id + "/images"))
            .header("Authorization", "Bearer " + token)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .header("Content-Type", "multipart/form-data")
            .POST(HttpRequest.BodyPublishers.ofString("invalid multipart"))
            .build();
    var response = http.send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).as(response.body()).isEqualTo(422);
    assertThat(StrictJson.parse(response.body()).path("code").asText())
        .isEqualTo("INVALID_MULTIPART");
  }

  @Test
  void refreshesPhotoUrls() throws Exception {
    String token = activeToken(), id = create(token).path("listingId").asText();
    String path = "/api/listings/" + id + "/images", key = UUID.randomUUID().toString();
    var first = multipart(path, "images", token, key, Map.of());
    assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
    String binding =
        StrictJson.canonical(StrictJson.stringify(List.of(owner.toString(), "POST", path, key)));
    byte[] encrypted =
        jdbc.queryForObject(
            "SELECT payload FROM idempotency_records WHERE actor=? AND path=? AND request_key=?",
            byte[].class,
            owner.toString(),
            path,
            UUID.fromString(key));
    var stored =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            StrictJson.parse(cipher.decrypt(encrypted, binding));
    var body =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            StrictJson.parse(stored.path("body").asText());
    var uri = URI.create(body.path("imageUris").get(0).asText());
    String expired = "https://storage.example.test" + uri.getPath() + "?token=expired";
    body.putArray("imageUris").add(expired);
    body.put("thumbnailUri", expired);
    stored.put("body", StrictJson.stringify(body));
    jdbc.update(
        "UPDATE idempotency_records SET payload=? WHERE actor=? AND path=? AND request_key=?",
        cipher.encrypt(StrictJson.stringify(stored), binding),
        owner.toString(),
        path,
        UUID.fromString(key));
    var replay = multipart(path, "images", token, key, Map.of());
    assertThat(replay.statusCode()).as(replay.body()).isEqualTo(200);
    var fresh = URI.create(StrictJson.parse(replay.body()).path("imageUris").get(0).asText());
    assertThat(fresh.getQuery()).doesNotContain("expired");
    var download =
        URI.create("http://127.0.0.1:" + port + fresh.getRawPath() + "?" + fresh.getRawQuery());
    assertThat(
            http.send(
                    HttpRequest.newBuilder(download).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray())
                .statusCode())
        .isEqualTo(200);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM listing_photos", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void keepsSearchSnapshot() throws Exception {
    String token = activeToken();
    for (int i = 0; i < 2; i++) publishPhotos(token, create(token).path("listingId").asText());
    var first = json("GET", "/api/v1/listings?limit=1", null, token);
    String cursor = StrictJson.parse(first.body()).path("page").path("nextCursor").asText();
    publishPhotos(token, create(token).path("listingId").asText());
    var next = json("GET", "/api/v1/listings?limit=1&cursor=" + cursor, null, token);
    assertThat(StrictJson.parse(next.body()).path("items")).hasSize(1);
    assertThat(StrictJson.parse(next.body()).path("page").path("hasNext").asBoolean()).isFalse();
    for (var entry :
        Map.of(
                "?unknown=x",
                400,
                "?minLat=1",
                400,
                "?minDeposit=100&maxDeposit=1",
                422,
                "?limit=51",
                400,
                "?hasSpace=1",
                400,
                "?limit=1&limit=2",
                400)
            .entrySet())
      assertThat(json("GET", "/api/v1/listings" + entry.getKey(), null, token).statusCode())
          .as(entry.getKey())
          .isEqualTo(entry.getValue());
    var all = json("GET", "/api/listings?maxPrice=31&maxDeposit=500", null, token);
    assertThat(StrictJson.parse(all.body()).path("items")).hasSize(3);
  }

  @Test
  void checksProductAccess() throws Exception {
    String token = activeToken(), other = tenant();
    String id = create(token).path("listingId").asText();
    publishPhotos(token, id);
    assertThat(json("POST", "/api/listings/" + id + "/scrap", null, other).statusCode())
        .isEqualTo(204);
    assertThat(json("PUT", "/api/v1/listings/" + id + "/scrap", null, other).statusCode())
        .isEqualTo(204);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM scraps", Integer.class)).isEqualTo(1);
    var scraps = json("GET", "/api/v1/users/me/scraps", null, other);
    contracts.validate("ListingPage", scraps.body());
    assertThat(StrictJson.parse(scraps.body()).path("items").get(0).path("isScrapped").asBoolean())
        .isTrue();
    var report =
        json(
            "POST",
            "/api/listings/" + id + "/reports",
            Map.of("reason", "가격 정보가 달라요"),
            other,
            "Idempotency-Key",
            UUID.randomUUID().toString());
    assertThat(report.statusCode()).as(report.body()).isEqualTo(201);
    var duplicate =
        json(
            "POST",
            "/api/v1/listings/" + id + "/reports",
            Map.of("reason", "WRONG_PRICE"),
            other,
            "Idempotency-Key",
            UUID.randomUUID().toString());
    assertThat(duplicate.statusCode()).isEqualTo(200);
    assertThat(duplicate.body()).isEqualTo(report.body());
    assertThat(
            json(
                    "POST",
                    "/api/v1/listings/" + id + "/reports",
                    Map.of("reason", "OTHER"),
                    token,
                    "Idempotency-Key",
                    UUID.randomUUID().toString())
                .statusCode())
        .isEqualTo(403);
    admin.report(
        UUID.fromString(StrictJson.parse(report.body()).path("reportId").asText()),
        "dismiss",
        "확인 완료",
        "LOCAL_ADMIN");
    assertThat(jdbc.queryForObject("SELECT status FROM reports", String.class))
        .isEqualTo("DISMISSED");
    assertThat(json("DELETE", "/api/listings/" + id + "/scrap", null, other).statusCode())
        .isEqualTo(204);
  }

  @Test
  void reviewsAgentEvidence() throws Exception {
    String token = activeToken();
    assertThat(json("GET", "/api/users/me/agent-profile", null, token).statusCode()).isEqualTo(404);
    var none = json("GET", "/api/v1/users/me/listing-agent-verification", null, token);
    contracts.validate("ListingAgentVerification", none.body());
    var applied =
        multipart(
            "/api/users/me/agent-profile",
            "document",
            token,
            UUID.randomUUID().toString(),
            Map.of(
                "name",
                "중개사",
                "licenseNumber",
                "license-123",
                "officeName",
                "둘러방",
                "documentName",
                "등록증"));
    assertThat(applied.statusCode()).as(applied.body()).isEqualTo(201);
    UUID application =
        jdbc.queryForObject("SELECT application_id FROM agent_applications", UUID.class);
    admin.agent(application, "approve", null, "LOCAL_ADMIN");
    var user = json("GET", "/api/users/me", null, token);
    assertThat(StrictJson.parse(user.body()).path("roles").toString()).contains("LISTING_AGENT");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_reviews", Integer.class))
        .isEqualTo(1);
    assertThatThrownBy(() -> admin.agent(application, "approve", null, "LOCAL_ADMIN"))
        .isInstanceOf(com.dulleobang.common.exception.ApiException.class);
    String other = tenant();
    String evidence = StrictJson.parse(applied.body()).path("documentFileId").asText();
    assertThat(json("GET", "/api/v1/assets/" + evidence + "/download", null, other).statusCode())
        .isEqualTo(404);
  }

  @Test
  void checksChatLifecycle() throws Exception {
    String token = activeToken(), other = tenant(), outsider = tenant();
    String id = create(token).path("listingId").asText();
    publishPhotos(token, id);
    String key = UUID.randomUUID().toString();
    var chat =
        json("POST", "/api/v1/chats", Map.of("listingId", id), other, "Idempotency-Key", key);
    assertThat(chat.statusCode()).as(chat.body()).isEqualTo(201);
    contracts.validate("Chat", chat.body());
    String chatId = StrictJson.parse(chat.body()).path("chatId").asText();
    assertThat(
            json(
                    "POST",
                    "/api/v1/chats",
                    Map.of("listingId", id),
                    other,
                    "Idempotency-Key",
                    UUID.randomUUID().toString())
                .statusCode())
        .isEqualTo(200);
    var message =
        json(
            "POST",
            "/api/chat/" + chatId,
            Map.of("message", "안녕하세요"),
            other,
            "Idempotency-Key",
            UUID.randomUUID().toString());
    assertThat(message.statusCode()).as(message.body()).isEqualTo(201);
    String messageId = StrictJson.parse(message.body()).path("messageId").asText();
    var ownerChat = json("GET", "/api/v1/chats/" + chatId, null, token);
    assertThat(StrictJson.parse(ownerChat.body()).path("unreadCount").asInt()).isEqualTo(1);
    assertThat(
            json(
                    "POST",
                    "/api/v1/chats/" + chatId + "/read",
                    Map.of("lastReadMessageId", messageId),
                    token)
                .statusCode())
        .isEqualTo(204);
    var details = json("GET", "/api/chat/" + chatId, null, token);
    assertThat(details.statusCode()).as(details.body()).isEqualTo(200);
    assertThat(StrictJson.parse(details.body()).path("messages")).hasSize(1);
    assertThat(StrictJson.parse(details.body()).path("unreadCount").asInt()).isZero();
    var second =
        json(
            "POST",
            "/api/chat/" + chatId,
            Map.of("message", "읽음 확인"),
            other,
            "Idempotency-Key",
            UUID.randomUUID().toString());
    assertThat(second.statusCode()).isEqualTo(201);
    assertThat(
            StrictJson.parse(json("GET", "/api/v1/chats/" + chatId, null, token).body())
                .path("unreadCount")
                .asInt())
        .isEqualTo(1);
    assertThat(json("GET", "/api/chat/" + chatId, null, token).statusCode()).isEqualTo(200);
    assertThat(
            StrictJson.parse(json("GET", "/api/v1/chats/" + chatId, null, token).body())
                .path("unreadCount")
                .asInt())
        .isZero();
    assertThat(json("GET", "/api/v1/chats/" + chatId, null, outsider).statusCode()).isEqualTo(404);
    var page = json("GET", "/api/v1/chats/" + chatId + "/messages?limit=1", null, token);
    assertThat(page.statusCode()).isEqualTo(200);
    contracts.validate("MessagePage", page.body());
    assertThat(
            json(
                    "PUT",
                    "/api/v1/listings/" + id + "/publication",
                    Map.of("status", "ARCHIVED"),
                    token,
                    "If-Match",
                    "\"2\"")
                .statusCode())
        .isEqualTo(200);
    var archived = json("GET", "/api/chat/" + chatId, null, other);
    assertThat(archived.statusCode()).as(archived.body()).isEqualTo(200);
    assertThat(StrictJson.parse(archived.body()).path("readOnly").asBoolean()).isTrue();
    assertThat(StrictJson.parse(archived.body()).path("listing").path("imageUris")).isEmpty();
    assertThat(
            json(
                    "POST",
                    "/api/chat/" + chatId,
                    Map.of("message", "blocked"),
                    other,
                    "Idempotency-Key",
                    UUID.randomUUID().toString())
                .statusCode())
        .isEqualTo(409);
  }

  @Test
  void runsHttpContracts() throws Exception {
    var process =
        new ProcessBuilder(
                System.getenv().getOrDefault("V2_PYTHON", "python"),
                "contract-tests/run.py",
                "--base-url",
                "http://127.0.0.1:" + port,
                "--profile",
                "legacy",
                "--report",
                "verification/spring-product-http-contracts.json")
            .inheritIO()
            .start();
    assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
    assertThat(process.exitValue()).isZero();
  }
}
