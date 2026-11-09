package com.dulleobang;

import static org.assertj.core.api.Assertions.*;

import com.dulleobang.admin.service.*;
import com.dulleobang.common.security.TokenVerifier;
import com.dulleobang.job.service.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class AdminOperationsIntegrationTest extends IntegrationTestSupport {
  @Autowired WorkerTokenService workers;
  @Autowired TokenVerifier verifier;
  @Autowired JobCommandService jobs;
  @Autowired JobMaintenanceService maintenance;
  @Autowired AdminQueryService queries;

  @Test
  void checksWorkerTokens() {
    String token = workers.issue("test-worker", 60);
    assertThat(verifier.verify(token, "worker").getSubject()).isEqualTo("test-worker");
    assertThatThrownBy(() -> verifier.verify(token, "user"))
        .isInstanceOf(com.dulleobang.common.exception.ApiException.class);
    assertThatThrownBy(() -> workers.issue("test-worker", 86401))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void readsQueueAndReviews() {
    UUID id = UUID.randomUUID();
    jdbc.update("INSERT INTO listings(listing_id,owner_id) VALUES(?,?)", id, owner);
    var job = jobs.create(owner, "ROOM_RECONSTRUCTION", "LISTING", id, id, null, null);
    assertThat(queries.queue(20)).isEmpty();
    maintenance.maintain();
    assertThat(queries.queue(20)).containsExactly(job.id());
    assertThat(queries.pending().get("applications")).isEmpty();
    assertThatThrownBy(() -> queries.queue(101)).isInstanceOf(IllegalArgumentException.class);
  }
}
