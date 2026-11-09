package com.dulleobang.infrastructure.scheduling;

import com.dulleobang.job.service.JobMaintenanceService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;

@Configuration
@EnableScheduling
@ConditionalOnProperty(
    name = "dulleobang.scheduler.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class JobScheduler {
  private final JobMaintenanceService maintenance;

  public JobScheduler(JobMaintenanceService maintenance) {
    this.maintenance = maintenance;
  }

  @Scheduled(fixedDelayString = "${dulleobang.scheduler.delay-ms:5000}")
  public void run() {
    maintenance.maintain();
  }
}
