package com.dulleobang.catalog.service;

import com.dulleobang.catalog.domain.CatalogItem;
import com.dulleobang.catalog.repository.CatalogRepository;
import com.dulleobang.common.contract.ContractValidator;
import com.dulleobang.common.exception.ApiException;
import com.dulleobang.common.json.StrictJson;
import com.dulleobang.furniture.mapper.FurnitureCategoryMapper;
import com.dulleobang.infrastructure.storage.AssetStorage;
import com.dulleobang.infrastructure.storage.CatalogSourceFiles;
import com.dulleobang.infrastructure.storage.CatalogSourceFiles.StagedAsset;
import com.dulleobang.space.validation.BoundsValidator;
import com.dulleobang.space.validation.GlbValidator;
import com.dulleobang.upload.domain.AssetFile;
import com.dulleobang.upload.repository.UploadRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CatalogImportService {
  private final CatalogRepository catalog;
  private final UploadRepository uploads;
  private final AssetStorage storage;
  private final CatalogSourceFiles source;
  private final GlbValidator glb;
  private final ContractValidator contracts;
  private final Clock clock;

  public CatalogImportService(
      CatalogRepository catalog,
      UploadRepository uploads,
      AssetStorage storage,
      CatalogSourceFiles source,
      GlbValidator glb,
      ContractValidator contracts,
      Clock clock) {
    this.catalog = catalog;
    this.uploads = uploads;
    this.storage = storage;
    this.source = source;
    this.glb = glb;
    this.contracts = contracts;
    this.clock = clock;
  }

  @Transactional(rollbackFor = IOException.class)
  public void importItem(Path directory) throws IOException {
    var article = directory.getFileName().toString();
    var catalogId = CatalogItem.ID_PREFIX + article;
    contracts.validate("CatalogId", StrictJson.stringify(catalogId));
    var meta = StrictJson.parse(source.metadata(directory));
    if (meta.has("article") && !article.equals(meta.path("article").asText()))
      throw new IllegalArgumentException("article does not match the directory");
    if (!meta.path("name").isTextual() || !meta.path("category").isTextual())
      throw new IllegalArgumentException("name and category must be strings");
    var name = meta.path("name").textValue().strip();
    var rawCategory = meta.path("category").textValue().strip().toLowerCase(Locale.ROOT);
    var category = FurnitureCategoryMapper.canonical(rawCategory).toUpperCase(Locale.ROOT);
    contracts.validate(
        "FurnitureCreate", StrictJson.stringify(Map.of("name", name, "category", category)));
    var dimensions = checked(meta, "dimensions", "Dimensions");
    var box = checked(meta, "boundingBox", "BoundingBox");
    var quality = checked(meta, "quality", "Quality");
    var sourceUrl = checked(meta, "sourceUrl", "CatalogSourceUrl").asText();
    BoundsValidator.box(dimensions, box);

    var item =
        new CatalogItem(
            UUID.randomUUID(),
            catalogId,
            name,
            category,
            dimensions,
            box,
            quality,
            sourceUrl,
            clock.instant());
    try (var model = stage(directory, meta, "model.glb", 256);
        var collision = stage(directory, meta, "collision.glb", 256);
        var photo = stage(directory, meta, "photo.jpg", 20)) {
      validateGlb(model, false, box);
      validateGlb(collision, true, box);

      UUID id = catalog.upsert(item);
      // Serialize with blob reclamation and take shared hashes in a consistent order.
      List.of(model, collision, photo).stream()
          .map(StagedAsset::sha256)
          .distinct()
          .sorted()
          .forEach(uploads::lockBlob);
      var previous = catalog.files(id);
      save(id, item, previous, model, "MODEL", "model/gltf-binary", "GLB_V2");
      save(id, item, previous, collision, "COLLISION", "model/gltf-binary", "GLB_V2");
      save(id, item, previous, photo, "THUMBNAIL", "image/jpeg", "JPEG");
    }
  }

  private void validateGlb(StagedAsset asset, boolean collision, JsonNode box) throws IOException {
    try {
      glb.validate(asset.bytes(), collision, box);
    } catch (ApiException ex) {
      throw new IllegalArgumentException(asset.filename() + ": " + ex.code(), ex);
    }
  }

  private JsonNode checked(JsonNode meta, String key, String schema) {
    var node = meta.path(key);
    contracts.validate(schema, StrictJson.stringify(node));
    return node;
  }

  private StagedAsset stage(Path directory, JsonNode meta, String filename, int maxMiB)
      throws IOException {
    var hash = meta.path("sha256").path(filename);
    if (hash.isMissingNode()) hash = meta.path("files").path(filename).path("sha256");
    if (!hash.isTextual() || !hash.asText().matches("[0-9a-fA-F]{64}"))
      throw new IllegalArgumentException("Missing or invalid SHA-256 for " + filename);
    return source.stage(directory, filename, maxMiB * 1024L * 1024L,
        hash.asText().toLowerCase(Locale.ROOT));
  }

  private void save(
      UUID itemId,
      CatalogItem item,
      Map<String, AssetFile> previous,
      StagedAsset asset,
      String role,
      String contentType,
      String format) {
    if (!storage.exists(asset.sha256())) storage.promote(asset.staged(), asset.sha256());
    var old = previous.get(role);
    if (old != null && old.sha256().equals(asset.sha256())) return;
    if (old != null) catalog.removeFile(itemId, old.id());
    var id = UUID.randomUUID();
    uploads.add(
        new AssetFile(
            id,
            null,
            null,
            "CATALOG",
            itemId,
            "CATALOG",
            id,
            asset.filename(),
            contentType,
            asset.size(),
            asset.sha256(),
            format,
            role,
            0,
            true,
            true,
            null,
            item.importedAt()));
  }

}
