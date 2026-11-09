package com.dulleobang.admin;

import com.dulleobang.admin.service.*;
import com.dulleobang.common.json.StrictJson;
import com.dulleobang.job.service.JobMaintenanceService;
import java.util.UUID;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "dulleobang.admin.command")
public class AdminCommandRunner implements ApplicationRunner {
  private final AdminReviewService reviews;
  private final Environment environment;
  private final WorkerTokenService workers;
  private final AdminQueryService queries;
  private final JobMaintenanceService maintenance;

  public AdminCommandRunner(
      AdminReviewService reviews,
      Environment environment,
      WorkerTokenService workers,
      AdminQueryService queries,
      JobMaintenanceService maintenance) {
    this.reviews = reviews;
    this.environment = environment;
    this.workers = workers;
    this.queries = queries;
    this.maintenance = maintenance;
  }

  public void run(ApplicationArguments args) {
    String command = environment.getRequiredProperty("dulleobang.admin.command");
    switch (command) {
      case "review-agent", "review-report" -> review(command);
      case "worker-token" ->
          System.out.println(
              workers.issue(
                  environment.getRequiredProperty("dulleobang.admin.worker-id"),
                  environment.getProperty("dulleobang.admin.seconds", Integer.class, 3600)));
      case "queue" ->
          System.out.println(
              StrictJson.stringify(
                  queries.queue(
                      environment.getProperty("dulleobang.admin.limit", Integer.class, 20))));
      case "pending-reviews" -> System.out.println(StrictJson.stringify(queries.pending()));
      case "maintenance" -> {
        maintenance.maintain();
        System.out.println("Maintenance completed.");
      }
      case "init" -> System.out.println("PostgreSQL migrations validated.");
      default -> throw new IllegalArgumentException("Unknown admin command");
    }
  }

  private void review(String command) {
    UUID id = UUID.fromString(environment.getRequiredProperty("dulleobang.admin.id"));
    String decision = environment.getRequiredProperty("dulleobang.admin.decision"),
        reason = environment.getProperty("dulleobang.admin.reason");
    if (command.equals("review-agent")) reviews.agent(id, decision, reason, "LOCAL_ADMIN");
    else reviews.report(id, decision, reason, "LOCAL_ADMIN");
    System.out.println("Review committed with an audit record.");
  }
}
