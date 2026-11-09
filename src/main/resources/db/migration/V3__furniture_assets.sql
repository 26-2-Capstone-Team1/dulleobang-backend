CREATE TABLE furniture (
 furniture_id uuid PRIMARY KEY,owner_id uuid NOT NULL REFERENCES users ON DELETE CASCADE,
 name varchar(80) NOT NULL,category varchar(30) NOT NULL,measured_dimensions jsonb,
 status varchar(15) NOT NULL DEFAULT 'DRAFT',resource_version bigint NOT NULL DEFAULT 1,
 active_job_id uuid,current_model_version integer,created_at timestamptz NOT NULL,updated_at timestamptz NOT NULL,deleted_at timestamptz,
 CHECK(resource_version>0),CHECK(status IN ('DRAFT','PROCESSING','READY','FAILED')),
 CHECK(category IN ('BED','SOFA','DESK','TABLE','CHAIR','WARDROBE','SHELF','REFRIGERATOR','WASHING_MACHINE','AIR_CONDITIONER','TV','OTHER'))
);
CREATE INDEX furniture_owner_live ON furniture(owner_id,created_at DESC,furniture_id) WHERE deleted_at IS NULL;
CREATE TABLE furniture_models (
 furniture_id uuid NOT NULL REFERENCES furniture ON DELETE CASCADE,model_version integer NOT NULL CHECK(model_version>0),
 model jsonb NOT NULL,PRIMARY KEY(furniture_id,model_version)
);
-- Listing identity exists before its P5 fields so uploads have real foreign keys.
CREATE TABLE listings (
 listing_id uuid PRIMARY KEY,owner_id uuid NOT NULL REFERENCES users ON DELETE CASCADE,
 status varchar(15) NOT NULL DEFAULT 'DRAFT',deleted_at timestamptz,
 CHECK(status IN ('DRAFT','PUBLISHED','ARCHIVED'))
);
CREATE TABLE upload_sessions (
 session_id uuid PRIMARY KEY,owner_id uuid NOT NULL REFERENCES users ON DELETE CASCADE,
 purpose varchar(30) NOT NULL,status varchar(15) NOT NULL DEFAULT 'OPEN',
 furniture_id uuid REFERENCES furniture ON DELETE CASCADE,listing_id uuid REFERENCES listings ON DELETE CASCADE,target_user_id uuid REFERENCES users ON DELETE CASCADE,
 created_at timestamptz NOT NULL,expires_at timestamptz NOT NULL,
 CHECK(num_nonnulls(furniture_id,listing_id,target_user_id)=1),CHECK(status IN ('OPEN','VERIFIED','CONSUMED','EXPIRED'))
);
CREATE TABLE assets (
 file_id uuid PRIMARY KEY,owner_id uuid NOT NULL REFERENCES users ON DELETE CASCADE,
 session_id uuid REFERENCES upload_sessions ON DELETE CASCADE,
 furniture_id uuid REFERENCES furniture ON DELETE CASCADE,listing_id uuid REFERENCES listings ON DELETE CASCADE,target_user_id uuid REFERENCES users ON DELETE CASCADE,
 purpose varchar(30) NOT NULL,client_file_id uuid NOT NULL,file_name varchar(120) NOT NULL,
 content_type varchar(60) NOT NULL,byte_size bigint NOT NULL CHECK(byte_size>0),sha256 char(64) NOT NULL,format varchar(40) NOT NULL,role varchar(30) NOT NULL,
 ordinal integer NOT NULL DEFAULT 0,stored boolean NOT NULL DEFAULT false,verified boolean NOT NULL DEFAULT false,
 derivative_id uuid REFERENCES assets,created_at timestamptz NOT NULL,
 CHECK(num_nonnulls(furniture_id,listing_id,target_user_id)=1),UNIQUE(session_id,client_file_id)
);
CREATE INDEX assets_session_order ON assets(session_id,ordinal);
