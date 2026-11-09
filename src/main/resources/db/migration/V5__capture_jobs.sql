CREATE TABLE room_states(state_id uuid PRIMARY KEY,owner_id uuid NOT NULL REFERENCES users ON DELETE CASCADE,listing_id uuid NOT NULL REFERENCES listings ON DELETE CASCADE,source_space_version integer NOT NULL,source_objects_version integer NOT NULL,removed_object_ids uuid[] NOT NULL,status varchar(15) NOT NULL DEFAULT 'PROCESSING',job_id uuid,bundle jsonb,source_bundle jsonb NOT NULL,created_at timestamptz NOT NULL,deleted_at timestamptz);
CREATE TABLE captures(capture_id uuid PRIMARY KEY,owner_id uuid NOT NULL REFERENCES users ON DELETE CASCADE,listing_id uuid REFERENCES listings ON DELETE CASCADE,furniture_id uuid REFERENCES furniture ON DELETE CASCADE,session_id uuid NOT NULL UNIQUE REFERENCES upload_sessions,archive_file_id uuid REFERENCES assets,latest_job_id uuid,created_at timestamptz NOT NULL,CHECK(num_nonnulls(listing_id,furniture_id)=1));
CREATE TABLE ai_jobs(job_id uuid PRIMARY KEY,owner_id uuid NOT NULL REFERENCES users ON DELETE CASCADE,type varchar(30) NOT NULL,target_type varchar(15) NOT NULL,target_id uuid NOT NULL,
 listing_id uuid REFERENCES listings ON DELETE CASCADE,furniture_id uuid REFERENCES furniture ON DELETE CASCADE,room_state_id uuid REFERENCES room_states ON DELETE CASCADE,capture_id uuid REFERENCES captures,
 status varchar(15) NOT NULL DEFAULT 'QUEUED',attempt_no integer NOT NULL DEFAULT 0,progress integer NOT NULL DEFAULT 0,stage varchar(60) NOT NULL DEFAULT 'QUEUED',
 result_type varchar(30),result_id uuid,result_version integer,error_code varchar(40),error_message varchar(500),retryable boolean,retry_of uuid REFERENCES ai_jobs,
 created_at timestamptz NOT NULL,updated_at timestamptz NOT NULL,finished_at timestamptz,lease_hash char(64),lease_expires_at timestamptz,attempt_started_at timestamptz,worker_id varchar(200),due_at timestamptz NOT NULL,
 CHECK(status IN ('QUEUED','RUNNING','RETRY_WAIT','COMPLETED','FAILED','CANCELED')),CHECK(attempt_no BETWEEN 0 AND 3),CHECK(progress BETWEEN 0 AND 100),
 CHECK((target_type='LISTING' AND listing_id=target_id AND furniture_id IS NULL AND room_state_id IS NULL) OR (target_type='FURNITURE' AND furniture_id=target_id AND room_state_id IS NULL) OR (target_type='ROOM_STATE' AND room_state_id=target_id AND furniture_id IS NULL)));
CREATE UNIQUE INDEX one_active_job_per_target ON ai_jobs(target_type,target_id) WHERE status IN ('QUEUED','RUNNING','RETRY_WAIT');
CREATE INDEX jobs_owner_status ON ai_jobs(owner_id,status);
ALTER TABLE captures ADD CONSTRAINT capture_latest_job FOREIGN KEY(latest_job_id) REFERENCES ai_jobs;
ALTER TABLE room_states ADD CONSTRAINT state_job FOREIGN KEY(job_id) REFERENCES ai_jobs;
ALTER TABLE listings ADD CONSTRAINT listing_active_job FOREIGN KEY(active_job_id) REFERENCES ai_jobs;
ALTER TABLE furniture ADD CONSTRAINT furniture_active_job FOREIGN KEY(active_job_id) REFERENCES ai_jobs;
CREATE TABLE job_outbox(event_id uuid PRIMARY KEY,job_id uuid NOT NULL REFERENCES ai_jobs ON DELETE CASCADE,available_at timestamptz NOT NULL,delivered_at timestamptz);
CREATE INDEX outbox_pending ON job_outbox(available_at) WHERE delivered_at IS NULL;
CREATE TABLE job_queue(job_id uuid PRIMARY KEY REFERENCES ai_jobs ON DELETE CASCADE,available_at timestamptz NOT NULL);
CREATE TABLE job_receipts(job_id uuid REFERENCES ai_jobs ON DELETE CASCADE,attempt_no integer,operation varchar(15),worker_id varchar(200) NOT NULL,lease_hash char(64) NOT NULL,body_hash char(64) NOT NULL,response jsonb NOT NULL,PRIMARY KEY(job_id,attempt_no,operation));
ALTER TABLE assets ADD COLUMN room_state_id uuid REFERENCES room_states ON DELETE CASCADE,ADD COLUMN job_id uuid REFERENCES ai_jobs ON DELETE CASCADE,ADD COLUMN attempt_no integer;
ALTER TABLE assets DROP CONSTRAINT assets_check;
ALTER TABLE assets ADD CHECK(num_nonnulls(furniture_id,listing_id,target_user_id,room_state_id)=1);
CREATE UNIQUE INDEX artifact_client_per_attempt ON assets(job_id,attempt_no,client_file_id) WHERE job_id IS NOT NULL;
CREATE UNIQUE INDEX artifact_role_per_attempt ON assets(job_id,attempt_no,role) WHERE job_id IS NOT NULL;
