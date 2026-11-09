package com.dulleobang;

import static org.assertj.core.api.Assertions.*;

import com.dulleobang.common.crypto.Hashes;
import com.dulleobang.common.exception.ApiException;
import com.dulleobang.upload.dto.UploadSource;
import com.dulleobang.upload.service.UploadIngestService;
import java.io.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.InputStreamSource;

class UploadIngestIntegrationTest extends IntegrationTestSupport {
  @Autowired UploadIngestService ingestion;

  private UploadSource source(byte[] raw, InputStreamSource reader) {
    return new UploadSource(
        UUID.randomUUID(), "image.jpg", "image/jpeg", null, raw.length, Hashes.sha256(raw), reader);
  }

  @Test
  void storesVerifiedBatch() throws Exception {
    byte[] raw = P6CaptureFixtures.jpeg();
    var file = source(raw, () -> new ByteArrayInputStream(raw));
    var batch = ingestion.store(owner, "LISTING_AGENT_DOCUMENT", "USER", owner, List.of(file));
    assertThat(batch.session().status()).isEqualTo("VERIFIED");
    assertThat(batch.files())
        .singleElement()
        .satisfies(
            stored -> {
              assertThat(stored.clientId()).isEqualTo(file.clientId());
              assertThat(stored.stored()).isTrue();
              assertThat(stored.verified()).isTrue();
            });
    assertThat(
            request("GET", "/api/v1/upload-sessions/" + batch.session().id(), activeToken())
                .statusCode())
        .isEqualTo(200);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_records", Integer.class))
        .isZero();
  }

  @Test
  void rollsBackFailedBatch() throws Exception {
    UUID target = UUID.randomUUID();
    jdbc.update("INSERT INTO listings(listing_id,owner_id) VALUES(?,?)", target, owner);
    byte[] raw = P6CaptureFixtures.jpeg();
    var first = source(raw, () -> new ByteArrayInputStream(raw));
    var broken =
        source(
            raw,
            () -> {
              throw new IOException("unavailable temporary source");
            });
    assertThatThrownBy(
            () ->
                ingestion.store(owner, "LISTING_PHOTO", "LISTING", target, List.of(first, broken)))
        .isInstanceOfSatisfying(
            ApiException.class,
            error -> {
              assertThat(error.status()).isEqualTo(503);
              assertThat(error.code()).isEqualTo("STORAGE_UNAVAILABLE");
            });
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM upload_sessions", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM assets", Integer.class)).isZero();
    var healthy = source(raw, () -> new ByteArrayInputStream(raw));
    var retried =
        ingestion.store(owner, "LISTING_PHOTO", "LISTING", target, List.of(first, healthy));
    assertThat(retried.session().status()).isEqualTo("VERIFIED");
    assertThat(retried.files())
        .hasSize(2)
        .allSatisfy(
            file -> {
              assertThat(file.stored()).isTrue();
              assertThat(file.verified()).isTrue();
              assertThat(file.derivative()).isNotNull();
            });
  }

  @Test
  void rejectsCorruptImages() throws Exception {
    byte[] raw = {1, 2, 3};
    var file = source(raw, () -> new ByteArrayInputStream(raw));
    assertThatThrownBy(
            () -> ingestion.store(owner, "LISTING_AGENT_DOCUMENT", "USER", owner, List.of(file)))
        .isInstanceOfSatisfying(
            ApiException.class,
            error -> {
              assertThat(error.status()).isEqualTo(422);
              assertThat(error.code()).isEqualTo("INVALID_IMAGE");
            });
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM upload_sessions", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM assets", Integer.class)).isZero();
  }

  @Test
  void enforcesBatchOwner() throws Exception {
    UUID target = UUID.randomUUID(), other = UUID.randomUUID();
    users.saveAndFlush(
        new com.dulleobang.user.domain.UserEntity(other, "다른 사용자", java.time.Instant.now()));
    jdbc.update("INSERT INTO listings(listing_id,owner_id) VALUES(?,?)", target, other);
    byte[] raw = P6CaptureFixtures.jpeg();
    var file =
        source(
            raw,
            () -> {
              throw new AssertionError("Must authorize before reading bytes");
            });
    assertThatThrownBy(
            () -> ingestion.store(owner, "LISTING_PHOTO", "LISTING", target, List.of(file)))
        .isInstanceOfSatisfying(
            ApiException.class, error -> assertThat(error.status()).isEqualTo(404));
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM upload_sessions", Integer.class)).isZero();
  }
}
