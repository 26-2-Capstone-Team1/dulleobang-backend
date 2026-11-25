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
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
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
  private final NamedParameterJdbcTemplate named;

  public CatalogRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
    this.named = new NamedParameterJdbcTemplate(jdbc);
  }

  public List<String> searchIds(String category, String search) {
    var sql = new StringBuilder("SELECT catalog_id FROM catalog_furniture WHERE TRUE");
    var params = new MapSqlParameterSource();
    if (category != null) {
      sql.append(" AND category = :category");
      params.addValue("category", category);
    }
    if (!search.isEmpty()) {
      sql.append(
          " AND (strpos(lower(name), :search) > 0"
              + " OR strpos(lower(substr(catalog_id, :articleStart)), :search) > 0)");
      params.addValue("search", search);
      params.addValue("articleStart", CatalogItem.ID_PREFIX.length() + 1);
    }
    return named.queryForList(sql.append(" ORDER BY catalog_id").toString(), params, String.class);
  }

  public List<CatalogItem> findOrdered(List<String> ids) {
    if (ids.isEmpty()) return List.of();
    return named.query(
        """
        SELECT catalog_furniture.*
        FROM jsonb_array_elements_text(CAST(:ids AS jsonb)) WITH ORDINALITY AS page(id, position)
        JOIN catalog_furniture ON catalog_furniture.catalog_id = page.id
        ORDER BY page.position
        """,
        Map.of("ids", StrictJson.stringify(ids)),
        ITEM);
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
