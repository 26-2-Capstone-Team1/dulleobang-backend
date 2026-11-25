package com.dulleobang.job.service;

import com.dulleobang.job.repository.*;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class JobMaintenanceService {
  private final JobRepository jobs;
  private final JobReconciliationService reconciliation;
  private final JobMaintenanceRepository maintenance;
  private final Clock clock;
  private final TransactionTemplate transactions;
  private final com.dulleobang.upload.service.AssetCleanupService cleanup;

  public JobMaintenanceService(
      JobRepository jobs,
      JobReconciliationService reconciliation,
      JobMaintenanceRepository maintenance,
      Clock clock,
      PlatformTransactionManager manager,
      com.dulleobang.upload.service.AssetCleanupService cleanup) {
    this.cleanup = cleanup;
    this.jobs = jobs;
    this.reconciliation = reconciliation;
    this.maintenance = maintenance;
    this.clock = clock;
    this.transactions = new TransactionTemplate(manager);
  }

  public void maintain() {
    for (var job : jobs.active()) reconciliation.reconcile(job.id());
    transactions.executeWithoutResult(
        tx -> {
          var now = clock.instant();
          maintenance.publish(now);
          maintenance.commonCleanup(now);
        });
    cleanup.clean();
  }
}
