package com.dulleobang;

import static org.assertj.core.api.Assertions.*;

import com.dulleobang.common.crypto.Hashes;
import com.dulleobang.infrastructure.storage.AssetStorage;
import com.dulleobang.upload.service.AssetCleanupService;
import java.io.ByteArrayInputStream;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class AssetRetentionIntegrationTest extends IntegrationTestSupport {
  @Autowired AssetCleanupService cleanup;
  @Autowired AssetStorage storage;
  @Autowired com.dulleobang.upload.repository.AssetRetentionRepository retention;
  @Autowired org.springframework.transaction.PlatformTransactionManager manager;

  @Test
  void waitsForBlobCommit() throws Exception {
    String sha = blob("racing-orphan-" + UUID.randomUUID(), true);
    UUID listing = listing(false);
    var locked = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
    try {
      var upload =
          executor.submit(
              () ->
                  new org.springframework.transaction.support.TransactionTemplate(manager)
                      .execute(
                          tx -> {
                            retention.lockBlob(sha);
                            locked.countDown();
                            try {
                              if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                                throw new IllegalStateException("upload gate timed out");
                              asset(listing, null, sha);
                            } catch (Exception ex) {
                              throw new IllegalStateException(ex);
                            }
                            return true;
                          }));
      assertThat(locked.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      var collecting = executor.submit(() -> cleanup.clean());
      boolean waiting = false;
      long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
      while (System.nanoTime() < deadline) {
        waiting =
            jdbc.queryForObject(
                    "SELECT COUNT(*) FROM pg_locks WHERE locktype='advisory' AND NOT granted",
                    Integer.class)
                > 0;
        if (waiting) break;
        Thread.sleep(20);
      }
      assertThat(waiting).as("collector must wait for the in-flight upload hash lock").isTrue();
      release.countDown();
      assertThat(upload.get(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      collecting.get(5, java.util.concurrent.TimeUnit.SECONDS);
      assertThat(Files.exists(storage.path(sha))).isTrue();
      assertThat(retention.retained(sha)).isTrue();
    } finally {
      release.countDown();
      executor.shutdownNow();
    }
  }

  String blob(String content, boolean old) throws Exception {
    byte[] raw = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    String sha = Hashes.sha256(raw);
    try (var staged = storage.stage(new ByteArrayInputStream(raw), raw.length, sha)) {
      storage.promote(staged, sha);
    }
    if (old)
      Files.setLastModifiedTime(
          storage.path(sha), FileTime.from(Instant.now().minusSeconds(172800)));
    return sha;
  }

  UUID listing(boolean deleted) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO listings(listing_id,owner_id,deleted_at) VALUES(?,?,?)",
        id,
        owner,
        deleted ? java.sql.Timestamp.from(Instant.now()) : null);
    return id;
  }

  UUID asset(UUID target, UUID session, String sha) throws Exception {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO"
            + " assets(file_id,owner_id,listing_id,session_id,purpose,client_file_id,file_name,content_type,byte_size,sha256,format,role,stored,verified,created_at)"
            + " VALUES(?,?,?,?,'LISTING_PHOTO',?,'photo.jpg','image/jpeg',?,?,'JPEG','PHOTO',TRUE,TRUE,CURRENT_TIMESTAMP)",
        id,
        owner,
        target,
        session,
        UUID.randomUUID(),
        Files.size(storage.path(sha)),
        sha);
    return id;
  }

  @Test
  void keepsSharedBlobs() throws Exception {
    String sha = blob("shared-content-" + UUID.randomUUID(), true);
    UUID dead = asset(listing(true), null, sha), live = asset(listing(false), null, sha);
    cleanup.clean();
    assertThat(
            jdbc.queryForObject("SELECT stored FROM assets WHERE file_id=?", Boolean.class, dead))
        .isFalse();
    assertThat(
            jdbc.queryForObject("SELECT stored FROM assets WHERE file_id=?", Boolean.class, live))
        .isTrue();
    assertThat(Files.exists(storage.path(sha))).isTrue();
  }

  @Test
  void cleansExpiredFiles() throws Exception {
    UUID listing = listing(false), session = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO upload_sessions(session_id,owner_id,purpose,listing_id,created_at,expires_at)"
            + " VALUES(?,?,'LISTING_PHOTO',?,CURRENT_TIMESTAMP-INTERVAL '4"
            + " days',CURRENT_TIMESTAMP-INTERVAL '2 days')",
        session,
        owner,
        listing);
    String expired = blob("expired-" + UUID.randomUUID(), true);
    UUID asset = asset(listing, session, expired);
    String orphan = blob("orphan-" + UUID.randomUUID(), true),
        recent = blob("recent-" + UUID.randomUUID(), false);
    Path staging =
        storage
            .path(recent)
            .getParent()
            .getParent()
            .resolve("staging")
            .resolve(UUID.randomUUID().toString());
    Files.write(staging, new byte[] {1});
    Files.setLastModifiedTime(staging, FileTime.from(Instant.now().minusSeconds(172800)));
    cleanup.clean();
    assertThat(
            jdbc.queryForObject("SELECT stored FROM assets WHERE file_id=?", Boolean.class, asset))
        .isFalse();
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM upload_sessions WHERE session_id=?", String.class, session))
        .isEqualTo("EXPIRED");
    assertThat(Files.exists(storage.path(expired))).isFalse();
    assertThat(Files.exists(storage.path(orphan))).isFalse();
    assertThat(Files.exists(storage.path(recent))).isTrue();
    assertThat(Files.exists(staging)).isFalse();
  }
}
