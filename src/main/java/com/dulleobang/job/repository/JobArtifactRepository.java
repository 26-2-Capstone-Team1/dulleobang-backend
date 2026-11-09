package com.dulleobang.job.repository;

import com.dulleobang.job.domain.JobRecord;
import com.dulleobang.upload.domain.AssetFile;
import com.dulleobang.upload.repository.UploadRepository;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JobArtifactRepository {
  private final JdbcTemplate jdbc;

  public JobArtifactRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<AssetFile> files(UUID job, int attempt) {
    return jdbc.query(
        "SELECT * FROM assets WHERE job_id=? AND attempt_no=? ORDER BY ordinal",
        UploadRepository.ASSET,
        job,
        attempt);
  }

  public void link(UUID file, UUID job, int attempt) {
    jdbc.update("UPDATE assets SET job_id=?,attempt_no=? WHERE file_id=?", job, attempt, file);
  }

  public boolean belongs(UUID file, JobRecord job, String role) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM assets WHERE file_id=? AND job_id=? AND attempt_no=? AND"
                + " role=?)",
            Boolean.class,
            file,
            job.id(),
            job.attempt(),
            role));
  }

  public boolean putAllowed(UUID file, Instant now) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM assets a JOIN ai_jobs j ON j.job_id=a.job_id WHERE"
                + " a.file_id=? AND a.attempt_no=j.attempt_no AND j.status='RUNNING' AND"
                + " j.lease_expires_at>? AND j.attempt_started_at>?::timestamptz - INTERVAL '30"
                + " minutes')",
            Boolean.class,
            file,
            java.sql.Timestamp.from(now),
            java.sql.Timestamp.from(now)));
  }

  public void lockForFile(UUID file) {
    jdbc.queryForList(
        "SELECT j.job_id FROM ai_jobs j JOIN assets a ON a.job_id=j.job_id WHERE a.file_id=? FOR"
            + " UPDATE OF j",
        file);
  }
}
