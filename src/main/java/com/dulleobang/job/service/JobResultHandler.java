package com.dulleobang.job.service;

import com.dulleobang.job.domain.JobRecord;
import com.fasterxml.jackson.databind.JsonNode;

public interface JobResultHandler {
  boolean supports(String type);

  JsonNode accept(JobRecord job, JsonNode result);
}
