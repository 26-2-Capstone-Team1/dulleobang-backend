package com.dulleobang.job.controller;

import com.dulleobang.job.service.WorkerQueueService;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/internal/ai-jobs")
public class WorkerQueueController {
  private final WorkerQueueService queue;

  public WorkerQueueController(WorkerQueueService queue) {
    this.queue = queue;
  }

  @GetMapping
  public Map<String, Object> poll(@RequestParam(defaultValue = "50") int limit) {
    return queue.poll(limit);
  }
}
