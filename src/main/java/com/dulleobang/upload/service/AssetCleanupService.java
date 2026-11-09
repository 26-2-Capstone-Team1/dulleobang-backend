package com.dulleobang.upload.service;

import com.dulleobang.infrastructure.storage.AssetReclaimer;
import com.dulleobang.upload.repository.*;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AssetCleanupService {
  private final UploadRepository assets;
  private final AssetRetentionRepository retention;
  private final AssetReclaimer storage;
  private final Clock clock;
  private final TransactionTemplate transactions;

  public AssetCleanupService(
      UploadRepository assets,
      AssetRetentionRepository retention,
      AssetReclaimer storage,
      Clock clock,
      PlatformTransactionManager manager) {
    this.assets = assets;
    this.retention = retention;
    this.storage = storage;
    this.clock = clock;
    this.transactions = new TransactionTemplate(manager);
  }

  public void clean() {
    var now = clock.instant();
    retention.expireSessions(now);
    for (var id : retention.candidates(now))
      transactions.executeWithoutResult(
          tx ->
              assets
                  .asset(id, false)
                  .ifPresent(
                      file -> {
                        retention.lockParent(file);
                        retention.lockBlob(file.sha256());
                        retention.retire(id, now);
                      }));
    var cutoff = now.minusSeconds(86400);
    for (String sha : storage.oldObjects(cutoff))
      transactions.executeWithoutResult(
          tx -> {
            retention.lockBlob(sha);
            if (!retention.retained(sha)) storage.deleteObject(sha, cutoff);
          });
    storage.cleanStaging(cutoff);
  }
}
