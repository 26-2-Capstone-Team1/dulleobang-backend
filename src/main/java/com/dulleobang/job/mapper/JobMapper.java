package com.dulleobang.job.mapper;

import com.dulleobang.common.json.ApiTime;
import com.dulleobang.job.domain.JobRecord;
import java.util.*;

public final class JobMapper {
  private JobMapper() {}

  public static Map<String, Object> response(JobRecord j, boolean mobile) {
    var v = new LinkedHashMap<String, Object>();
    v.put("jobId", j.id());
    v.put("type", j.type());
    v.put("status", j.status());
    v.put("targetType", j.targetType());
    v.put("targetId", j.targetId());
    v.put("attemptNo", j.attempt());
    v.put("progressPercent", j.progress());
    v.put("stage", j.stage());
    v.put("result", j.result());
    v.put("error", j.error());
    v.put("retryOfJobId", j.retryOf());
    v.put("createdAt", ApiTime.format(j.createdAt()));
    v.put("updatedAt", ApiTime.format(j.updatedAt()));
    v.put("finishedAt", j.finishedAt() == null ? null : ApiTime.format(j.finishedAt()));
    v.put("pollAfterSeconds", j.active() ? 3 : 0);
    if (mobile) {
      v.put("progress", j.progress());
      v.put("message", j.error() == null ? null : j.error().path("message").asText());
    }
    return v;
  }
}
