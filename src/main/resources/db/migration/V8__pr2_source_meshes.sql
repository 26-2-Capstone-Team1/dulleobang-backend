-- Primary roles remain unique per attempt. A PR #2 result retains multiple source GLBs.
DROP INDEX artifact_role_per_attempt;
CREATE UNIQUE INDEX artifact_role_per_attempt ON assets(job_id,attempt_no,role)
WHERE job_id IS NOT NULL AND role <> 'SOURCE_MESH';
