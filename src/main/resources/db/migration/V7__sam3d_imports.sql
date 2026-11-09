CREATE TABLE sam3d_imports (
 furniture_id uuid PRIMARY KEY REFERENCES furniture ON DELETE CASCADE,
 owner_id uuid NOT NULL REFERENCES users ON DELETE CASCADE,
 studio_job_id char(32) NOT NULL CHECK(studio_job_id ~ '^[0-9a-f]{32}$'),
 request_hash char(64) NOT NULL,
 collision_strategy varchar(30) NOT NULL CHECK(collision_strategy='BOUNDING_BOX'),
 created_at timestamptz NOT NULL
);
CREATE INDEX sam3d_import_lookup ON sam3d_imports(owner_id,studio_job_id);
