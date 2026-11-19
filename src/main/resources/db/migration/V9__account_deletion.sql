ALTER TABLE sessions DROP CONSTRAINT sessions_user_id_fkey,
    ADD CONSTRAINT sessions_user_id_fkey FOREIGN KEY (user_id) REFERENCES users ON DELETE CASCADE;
ALTER TABLE cursor_snapshots DROP CONSTRAINT cursor_snapshots_owner_id_fkey,
    ADD CONSTRAINT cursor_snapshots_owner_id_fkey FOREIGN KEY (owner_id) REFERENCES users ON DELETE CASCADE;

-- These references remain immediate outside the account deletion transaction.
ALTER TABLE assets ALTER CONSTRAINT assets_derivative_id_fkey DEFERRABLE INITIALLY IMMEDIATE;
ALTER TABLE listing_photos ALTER CONSTRAINT listing_photos_source_file_id_fkey DEFERRABLE INITIALLY IMMEDIATE;
ALTER TABLE listing_photos ALTER CONSTRAINT listing_photos_file_id_fkey DEFERRABLE INITIALLY IMMEDIATE;
ALTER TABLE agent_applications ALTER CONSTRAINT agent_applications_document_file_id_fkey DEFERRABLE INITIALLY IMMEDIATE;
ALTER TABLE agent_profiles ALTER CONSTRAINT agent_profiles_current_application_id_fkey DEFERRABLE INITIALLY IMMEDIATE;
ALTER TABLE agent_profiles ALTER CONSTRAINT agent_profiles_approved_application_id_fkey DEFERRABLE INITIALLY IMMEDIATE;
ALTER TABLE captures ALTER CONSTRAINT captures_session_id_fkey DEFERRABLE INITIALLY IMMEDIATE;
ALTER TABLE captures ALTER CONSTRAINT captures_archive_file_id_fkey DEFERRABLE INITIALLY IMMEDIATE;
ALTER TABLE captures ALTER CONSTRAINT capture_latest_job DEFERRABLE INITIALLY IMMEDIATE;
ALTER TABLE ai_jobs ALTER CONSTRAINT ai_jobs_capture_id_fkey DEFERRABLE INITIALLY IMMEDIATE;
ALTER TABLE ai_jobs ALTER CONSTRAINT ai_jobs_retry_of_fkey DEFERRABLE INITIALLY IMMEDIATE;
ALTER TABLE room_states ALTER CONSTRAINT state_job DEFERRABLE INITIALLY IMMEDIATE;
ALTER TABLE listings ALTER CONSTRAINT listing_active_job DEFERRABLE INITIALLY IMMEDIATE;
ALTER TABLE furniture ALTER CONSTRAINT furniture_active_job DEFERRABLE INITIALLY IMMEDIATE;
ALTER TABLE room_states ALTER CONSTRAINT state_source_space DEFERRABLE INITIALLY IMMEDIATE;
ALTER TABLE placement_sets ALTER CONSTRAINT placement_sets_room_state_id_fkey DEFERRABLE INITIALLY IMMEDIATE;
ALTER TABLE placement_sets ALTER CONSTRAINT placement_sets_listing_id_space_version_fkey DEFERRABLE INITIALLY IMMEDIATE;
ALTER TABLE placement_items ALTER CONSTRAINT placement_items_furniture_id_model_version_fkey DEFERRABLE INITIALLY IMMEDIATE;

CREATE TABLE asset_deletion_queue (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    sha256 char(64) NOT NULL
);
CREATE INDEX assets_stored_hash ON assets (sha256) WHERE stored;

CREATE FUNCTION enqueue_removed_asset() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO asset_deletion_queue (sha256) VALUES (OLD.sha256);
    RETURN OLD;
END;
$$;

CREATE TRIGGER record_removed_asset AFTER DELETE ON assets
FOR EACH ROW EXECUTE FUNCTION enqueue_removed_asset();
