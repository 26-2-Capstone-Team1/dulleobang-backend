CREATE TABLE catalog_furniture (
    catalog_id varchar(37) PRIMARY KEY,
    item_id uuid NOT NULL UNIQUE,
    name varchar(80) NOT NULL,
    category varchar(30) NOT NULL,
    dimensions jsonb NOT NULL,
    bounding_box jsonb NOT NULL,
    quality jsonb NOT NULL,
    source_url varchar(2048) NOT NULL,
    imported_at timestamptz NOT NULL
);

-- Catalog assets belong to the system, not to a login account. All other assets
-- still require their original owner and exactly one target.
ALTER TABLE assets
    ADD COLUMN catalog_item_id uuid REFERENCES catalog_furniture(item_id) ON DELETE CASCADE,
    ALTER COLUMN owner_id DROP NOT NULL,
    DROP CONSTRAINT assets_check,
    ADD CONSTRAINT assets_one_target
        CHECK (num_nonnulls(furniture_id, listing_id, target_user_id, room_state_id, catalog_item_id) = 1),
    ADD CONSTRAINT assets_catalog_owner CHECK (
        (catalog_item_id IS NULL AND owner_id IS NOT NULL AND purpose <> 'CATALOG') OR
        (catalog_item_id IS NOT NULL AND owner_id IS NULL AND purpose = 'CATALOG'
         AND session_id IS NULL AND job_id IS NULL AND stored AND verified
         AND role IN ('MODEL', 'COLLISION', 'THUMBNAIL'))
    );

CREATE UNIQUE INDEX assets_catalog_role ON assets (catalog_item_id, role)
    WHERE catalog_item_id IS NOT NULL;
