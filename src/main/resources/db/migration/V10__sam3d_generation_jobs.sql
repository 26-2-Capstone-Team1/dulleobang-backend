CREATE TABLE sam3d_generation_jobs (
  studio_job_id char(32) PRIMARY KEY CHECK(studio_job_id ~ '^[0-9a-f]{32}$'),
  -- Keep a revoked job ID from falling back to the legacy Studio import after deletion.
  owner_id uuid REFERENCES users ON DELETE SET NULL
);
CREATE INDEX sam3d_generation_jobs_owner ON sam3d_generation_jobs(owner_id);
