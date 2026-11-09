package com.dulleobang.job.repository;

import com.dulleobang.job.domain.JobRecord;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;

@Repository
public class JobRepository {
  private final JdbcTemplate jdbc;
  private final JobTargetRepository targets;

  public JobRepository(JdbcTemplate jdbc, JobTargetRepository targets) {
    this.jdbc = jdbc;
    this.targets = targets;
  }

  private static java.sql.Timestamp ts(Instant t) {
    return t == null ? null : java.sql.Timestamp.from(t);
  }

  private final RowMapper<JobRecord> mapper = new JobRowMapper();

  public Optional<JobRecord> find(UUID id, boolean lock) {
    return jdbc
        .query("SELECT * FROM ai_jobs WHERE job_id=?" + (lock ? " FOR UPDATE" : ""), mapper, id)
        .stream()
        .findFirst();
  }

  public void lockOwner(UUID owner) {
    targets.lockOwner(owner);
  }

  public boolean targetLive(String type, UUID id) {
    return targets.targetLive(type, id);
  }

  public boolean lockTarget(String type, UUID id) {
    return targets.lockTarget(type, id);
  }

  public void pointer(JobRecord job, boolean active, boolean success, Instant now) {
    targets.pointer(job, active, success, now);
  }

  public int activeCount(UUID owner) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM ai_jobs WHERE owner_id=? AND status IN"
            + " ('QUEUED','RUNNING','RETRY_WAIT')",
        Integer.class,
        owner);
  }

  public boolean targetActive(String type, UUID id) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM ai_jobs WHERE target_type=? AND target_id=? AND status IN"
                + " ('QUEUED','RUNNING','RETRY_WAIT'))",
            Boolean.class,
            type,
            id));
  }

  public void create(JobRecord j) {
    jdbc.update(
        "INSERT INTO"
            + " ai_jobs(job_id,owner_id,type,target_type,target_id,listing_id,furniture_id,room_state_id,capture_id,retry_of,created_at,updated_at,due_at)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
        j.id(),
        j.owner(),
        j.type(),
        j.targetType(),
        j.targetId(),
        j.listingId(),
        j.targetType().equals("FURNITURE") ? j.targetId() : null,
        j.targetType().equals("ROOM_STATE") ? j.targetId() : null,
        j.captureId(),
        j.retryOf(),
        ts(j.createdAt()),
        ts(j.updatedAt()),
        ts(j.dueAt()));
    outbox(j.id(), j.dueAt());
  }

  public void outbox(UUID id, Instant due) {
    jdbc.update(
        "INSERT INTO job_outbox(event_id,job_id,available_at) VALUES(?,?,?)",
        UUID.randomUUID(),
        id,
        ts(due));
  }

  public void claim(UUID id, String worker, String hash, Instant now) {
    jdbc.update(
        "UPDATE ai_jobs SET"
            + " status='RUNNING',stage='PREPROCESSING',attempt_no=attempt_no+1,progress=0,error_code=NULL,error_message=NULL,retryable=NULL,worker_id=?,lease_hash=?,lease_expires_at=?,attempt_started_at=?,updated_at=?"
            + " WHERE job_id=?",
        worker,
        hash,
        ts(now.plusSeconds(120)),
        ts(now),
        ts(now),
        id);
    dequeue(id);
  }

  public void heartbeat(UUID id, int progress, String stage, Instant until, Instant now) {
    jdbc.update(
        "UPDATE ai_jobs SET progress=?,stage=?,lease_expires_at=?,updated_at=? WHERE job_id=?",
        progress,
        stage,
        ts(until),
        ts(now),
        id);
  }

  public void fail(
      UUID id,
      String code,
      String message,
      boolean retryable,
      boolean retry,
      Instant due,
      Instant now) {
    jdbc.update(
        "UPDATE ai_jobs SET"
            + " status=?,stage=?,error_code=?,error_message=?,retryable=?,due_at=?,updated_at=?,finished_at=?"
            + " WHERE job_id=?",
        retry ? "RETRY_WAIT" : "FAILED",
        retry ? "RETRY_WAIT" : "FAILED",
        code,
        message,
        retryable,
        ts(due),
        ts(now),
        retry ? null : ts(now),
        id);
    if (retry) outbox(id, due);
    else dequeue(id);
  }

  public void terminal(
      UUID id, String status, com.fasterxml.jackson.databind.JsonNode result, Instant now) {
    jdbc.update(
        "UPDATE ai_jobs SET"
            + " status=?,stage=?,result_type=?,result_id=?,result_version=?,error_code=NULL,error_message=NULL,retryable=NULL,progress=CASE"
            + " WHEN ?='COMPLETED' THEN 100 ELSE progress END,updated_at=?,finished_at=? WHERE"
            + " job_id=?",
        status,
        status,
        result == null ? null : result.path("resourceType").asText(),
        result == null ? null : UUID.fromString(result.path("resourceId").asText()),
        result == null ? null : result.path("version").asInt(),
        status,
        ts(now),
        ts(now),
        id);
    dequeue(id);
  }

  public void dequeue(UUID id) {
    jdbc.update("DELETE FROM job_queue WHERE job_id=?", id);
  }

  public List<JobRecord> active() {
    return jdbc.query(
        "SELECT * FROM ai_jobs WHERE status IN ('RUNNING','QUEUED','RETRY_WAIT') ORDER BY"
            + " created_at",
        mapper);
  }

  public List<JobRecord> targetJobs(String type, UUID id) {
    return jdbc.query(
        "SELECT * FROM ai_jobs WHERE (target_type=? AND target_id=?) OR (?='LISTING' AND"
            + " listing_id=?) ORDER BY job_id",
        mapper,
        type,
        id,
        type,
        id);
  }
}
