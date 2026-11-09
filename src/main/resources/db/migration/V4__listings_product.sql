ALTER TABLE listings ADD COLUMN title varchar(100),ADD COLUMN description text,
 ADD COLUMN road_address varchar(200) NOT NULL DEFAULT '',ADD COLUMN detail_address varchar(200),ADD COLUMN region_code char(10),
 ADD COLUMN latitude double precision NOT NULL DEFAULT 0 CHECK(latitude BETWEEN -90 AND 90),ADD COLUMN longitude double precision NOT NULL DEFAULT 0 CHECK(longitude BETWEEN -180 AND 180),
 ADD COLUMN lease_type varchar(10) NOT NULL DEFAULT 'MONTHLY' CHECK(lease_type IN ('MONTHLY','JEONSE')),
 ADD COLUMN deposit bigint NOT NULL DEFAULT 0 CHECK(deposit BETWEEN 0 AND 1000000000000),ADD COLUMN monthly_rent bigint NOT NULL DEFAULT 1 CHECK(monthly_rent BETWEEN 0 AND 1000000000000),ADD COLUMN maintenance_fee bigint CHECK(maintenance_fee BETWEEN 0 AND 1000000000000),
 ADD COLUMN exclusive_area double precision NOT NULL DEFAULT 1 CHECK(exclusive_area>0),ADD COLUMN supply_area double precision,
 ADD COLUMN floor integer,ADD COLUMN total_floors integer,ADD COLUMN room_type varchar(30) NOT NULL DEFAULT 'ONE_ROOM',ADD COLUMN room_count integer,ADD COLUMN bathroom_count integer,
 ADD COLUMN available_from varchar(80),ADD COLUMN living_environment varchar(30),ADD COLUMN rental_options text[] NOT NULL DEFAULT '{}',
 ADD COLUMN resource_version bigint NOT NULL DEFAULT 1 CHECK(resource_version>0),ADD COLUMN current_space_version integer,ADD COLUMN active_job_id uuid,
 ADD COLUMN created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,ADD COLUMN updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP;
CREATE INDEX listings_search_live ON listings(status,created_at DESC,listing_id) WHERE deleted_at IS NULL;
CREATE INDEX listings_map_live ON listings(latitude,longitude) WHERE deleted_at IS NULL AND status='PUBLISHED';
CREATE TABLE listing_photos(listing_id uuid NOT NULL REFERENCES listings ON DELETE CASCADE,ordinal integer NOT NULL,source_file_id uuid NOT NULL REFERENCES assets,file_id uuid NOT NULL REFERENCES assets,PRIMARY KEY(listing_id,ordinal),UNIQUE(listing_id,source_file_id));
CREATE TABLE scraps(user_id uuid REFERENCES users ON DELETE CASCADE,listing_id uuid REFERENCES listings ON DELETE CASCADE,saved_at timestamptz NOT NULL,PRIMARY KEY(user_id,listing_id));
CREATE TABLE reports(report_id uuid PRIMARY KEY,listing_id uuid NOT NULL REFERENCES listings ON DELETE CASCADE,reporter_id uuid NOT NULL REFERENCES users ON DELETE CASCADE,reason varchar(30) NOT NULL,detail varchar(1000),status varchar(15) NOT NULL DEFAULT 'OPEN',resolution text,created_at timestamptz NOT NULL,reviewed_at timestamptz);
CREATE INDEX report_dedup ON reports(reporter_id,listing_id,created_at DESC);
CREATE TABLE agent_applications(application_id uuid PRIMARY KEY,user_id uuid NOT NULL REFERENCES users ON DELETE CASCADE,status varchar(15) NOT NULL,name varchar(80) NOT NULL,license_number varchar(80) NOT NULL,office_name varchar(150) NOT NULL,phone varchar(32),document_file_id uuid NOT NULL REFERENCES assets,document_name varchar(255),requested_at timestamptz NOT NULL,reviewed_at timestamptz,reject_reason text);
CREATE TABLE agent_profiles(user_id uuid PRIMARY KEY REFERENCES users ON DELETE CASCADE,current_application_id uuid NOT NULL REFERENCES agent_applications,approved_application_id uuid REFERENCES agent_applications);
CREATE TABLE chats(chat_id uuid PRIMARY KEY,listing_id uuid NOT NULL REFERENCES listings ON DELETE CASCADE,inquirer_id uuid NOT NULL REFERENCES users ON DELETE CASCADE,owner_id uuid NOT NULL REFERENCES users ON DELETE CASCADE,listing_snapshot jsonb NOT NULL,created_at timestamptz NOT NULL,updated_at timestamptz NOT NULL,UNIQUE(listing_id,inquirer_id),CHECK(inquirer_id<>owner_id));
CREATE TABLE chat_messages(message_id uuid PRIMARY KEY,chat_id uuid NOT NULL REFERENCES chats ON DELETE CASCADE,sender_id uuid NOT NULL REFERENCES users ON DELETE CASCADE,text varchar(1000) NOT NULL,sequence bigserial UNIQUE NOT NULL,sent_at timestamptz NOT NULL);
CREATE INDEX messages_chat_sequence ON chat_messages(chat_id,sequence DESC);
CREATE TABLE chat_reads(chat_id uuid REFERENCES chats ON DELETE CASCADE,user_id uuid REFERENCES users ON DELETE CASCADE,last_sequence bigint NOT NULL,PRIMARY KEY(chat_id,user_id));
CREATE TABLE admin_reviews(review_id uuid PRIMARY KEY,kind varchar(30) NOT NULL,resource_id uuid NOT NULL,reviewer varchar(200) NOT NULL,decision varchar(30) NOT NULL,reason text,created_at timestamptz NOT NULL);
