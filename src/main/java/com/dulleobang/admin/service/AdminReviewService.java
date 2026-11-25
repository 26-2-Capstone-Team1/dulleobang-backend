package com.dulleobang.admin.service;

import com.dulleobang.admin.repository.AdminReviewRepository;
import com.dulleobang.common.exception.ApiException;
import com.dulleobang.report.repository.ReportRepository;
import com.dulleobang.verification.repository.AgentVerificationRepository;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminReviewService {
  private final AdminReviewRepository audit;
  private final AgentVerificationRepository agents;
  private final ReportRepository reports;
  private final Clock clock;

  public AdminReviewService(
      AdminReviewRepository audit,
      AgentVerificationRepository agents,
      ReportRepository reports,
      Clock clock) {
    this.audit = audit;
    this.agents = agents;
    this.reports = reports;
    this.clock = clock;
  }

  @Transactional
  public void agent(UUID id, String decision, String reason, String reviewer) {
    if (!Set.of("approve", "reject").contains(decision))
      throw new ApiException(422, "INVALID_DECISION");
    if (decision.equals("reject")
        && (reason == null || reason.isBlank() || reason.strip().length() > 2000))
      throw new ApiException(422, "REJECTION_REASON_REQUIRED");
    var application =
        agents.lock(id).orElseThrow(() -> new ApiException(404, "RESOURCE_NOT_FOUND"));
    agents.lockUser(application.user());
    if (!application.status().equals("PENDING") || !agents.isCurrent(application.user(), id))
      throw new ApiException(409, "APPLICATION_NOT_PENDING");
    agents.review(application, decision.equals("approve"), reason, clock.instant());
    audit.add("AGENT_VERIFICATION_REVIEW", id, reviewer, decision, reason, clock.instant());
  }

  @Transactional
  public void report(UUID id, String decision, String reason, String reviewer) {
    if (!Set.of("resolve", "dismiss").contains(decision)
        || reason == null
        || reason.isBlank()
        || reason.strip().length() > 2000) throw new ApiException(422, "INVALID_DECISION");
    if (!reports.review(
        id, decision.equals("resolve") ? "RESOLVED" : "DISMISSED", reason.strip(), clock.instant()))
      throw new ApiException(409, "REPORT_ALREADY_REVIEWED");
    audit.add("LISTING_REPORT_REVIEW", id, reviewer, decision, reason, clock.instant());
  }
}
