package com.dulleobang.catalog.repository;

import com.dulleobang.catalog.domain.CatalogItem;
import com.dulleobang.common.json.StrictJson;
import com.dulleobang.upload.domain.AssetFile;
import com.dulleobang.upload.repository.UploadRepository;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class CatalogRepository {
  private static final RowMapper<CatalogItem> ITEM =
      (rs, row) ->
          new CatalogItem(
              rs.getObject("item_id", UUID.class),
              rs.getString("catalog_id"),
              rs.getString("name"),
              rs.getString("category"),
              StrictJson.parse(rs.getString("dimensions")),
              StrictJson.parse(rs.getString("bounding_box")),
              StrictJson.parse(rs.getString("quality")),
              rs.getString("source_url"),
              rs.getTimestamp("imported_at").toInstant());

  private final JdbcTemplate jdbc;

  public CatalogRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<CatalogItem> list() {
    return jdbc.query("SELECT * FROM catalog_furniture ORDER BY catalog_id", ITEM);
  }

  public Optional<CatalogItem> find(String catalogId, boolean lock) {
    return jdbc
        .query(
            "SELECT * FROM catalog_furniture WHERE catalog_id=?" + (lock ? " FOR SHARE" : ""),
            ITEM,
            catalogId)
        .stream()
        .findFirst();
  }

  public Map<String, AssetFile> files(UUID itemId) {
    return jdbc
        .query("SELECT * FROM assets WHERE catalog_item_id=?", UploadRepository.ASSET, itemId)
        .stream()
        .collect(Collectors.toMap(AssetFile::role, Function.identity()));
  }

  public UUID upsert(CatalogItem item) {
    return jdbc.queryForObject(
        """
        INSERT INTO catalog_furniture(item_id,catalog_id,name,category,dimensions,bounding_box,
            quality,source_url,imported_at)
        VALUES (?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?,?)
        ON CONFLICT(catalog_id) DO UPDATE SET name=EXCLUDED.name,category=EXCLUDED.category,
            dimensions=EXCLUDED.dimensions,bounding_box=EXCLUDED.bounding_box,quality=EXCLUDED.quality,
            source_url=EXCLUDED.source_url,imported_at=EXCLUDED.imported_at
        RETURNING item_id
        """,
        UUID.class,
        item.itemId(),
        item.catalogId(),
        item.name(),
        item.category(),
        StrictJson.stringify(item.dimensions()),
        StrictJson.stringify(item.boundingBox()),
        StrictJson.stringify(item.quality()),
        item.sourceUrl(),
        Timestamp.from(item.importedAt()));
  }

  public void removeFile(UUID itemId, UUID fileId) {
    jdbc.update("DELETE FROM assets WHERE catalog_item_id=? AND file_id=?", itemId, fileId);
  }
}
