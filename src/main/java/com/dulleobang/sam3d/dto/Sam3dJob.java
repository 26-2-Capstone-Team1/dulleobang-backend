package com.dulleobang.sam3d.dto;

public record Sam3dJob(String studioJobId, Status status) {
  public enum Status {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED
  }
}
