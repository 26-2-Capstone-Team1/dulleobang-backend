package com.dulleobang.catalog.service;

import com.dulleobang.catalog.domain.CatalogItem;
import com.dulleobang.catalog.repository.CatalogRepository;
import com.dulleobang.common.exception.ApiException;
import com.dulleobang.common.idempotency.StoredResponse;
import com.dulleobang.common.idempotency.service.IdempotencyService;
import com.dulleobang.common.json.StrictJson;
import com.dulleobang.common.pagination.service.CursorService;
import com.dulleobang.furniture.mapper.FurnitureMapper;
import com.dulleobang.furniture.repository.FurnitureRepository;
import com.dulleobang.furniture.service.FurnitureQueryService;
import com.dulleobang.infrastructure.storage.AssetStorage;
import com.dulleobang.space.repository.SpaceResultRepository;
import com.dulleobang.upload.domain.AssetFile;
import com.dulleobang.upload.repository.UploadRepository;
import com.dulleobang.upload.service.AssetPresentationService;
import com.dulleobang.upload.service.AssetResponseRefresher;
import com.dulleobang.upload.service.SignedUrlService;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CatalogService {
  private final CatalogRepository catalog;
  private final CursorService cursors;
  private final AssetPresentationService presentation;
  private final FurnitureQueryService furnitureQuery;
  private final FurnitureRepository furniture;
  private final IdempotencyService idempotency;
  private final UploadRepository uploads;
  private final AssetStorage storage;
  private final SignedUrlService signatures;
  private final AssetResponseRefresher responses;
  private final SpaceResultRepository models;
  private final Clock clock;

  public CatalogService(
      CatalogRepository catalog,
      CursorService cursors,
      AssetPresentationService presentation,
      FurnitureQueryService furnitureQuery,
      FurnitureRepository furniture,
      IdempotencyService idempotency,
      UploadRepository uploads,
      AssetStorage storage,
      SignedUrlService signatures,
      AssetResponseRefresher responses,
      SpaceResultRepository models,
      Clock clock) {
    this.catalog = catalog;
    this.cursors = cursors;
    this.presentation = presentation;
    this.furnitureQuery = furnitureQuery;
    this.furniture = furniture;
    this.idempotency = idempotency;
    this.uploads = uploads;
    this.storage = storage;
    this.signatures = signatures;
    this.responses = responses;
    this.models = models;
    this.clock = clock;
  }

  @Transactional(isolation = Isolation.REPEATABLE_READ)
  public Map<String, Object> getCatalogFurniture(
      UUID actor, String path, String category, String q, int limit, String cursor) {
    String search = q == null ? "" : q.strip().toLowerCase(Locale.ROOT);
    var filters = new LinkedHashMap<String, Object>();
    filters.put("limit", limit);
    if (category != null) filters.put("category", category);
    if (!search.isEmpty()) filters.put("q", search);
    var matches = new LinkedHashMap<String, CatalogItem>();
    for (var item : catalog.list()) {
      if (category != null && !category.equals(item.category())) continue;
      if (!item.name().toLowerCase(Locale.ROOT).contains(search)
          && !item.article().toLowerCase(Locale.ROOT).contains(search)) continue;
      matches.put(item.catalogId(), item);
    }
    var page =
        cursor == null
            ? cursors.start(actor, path, filters, matches.keySet().stream().toList(), limit)
            : cursors.resume(actor, path, filters, cursor, limit, matches::containsKey);
    var info = new LinkedHashMap<String, Object>();
    info.put("nextCursor", page.nextCursor());
    info.put("hasNext", page.hasNext());
    return Map.of(
        "items",
        page.ids().stream().map(id -> present(matches.get(id), false)).toList(),
        "page",
        info);
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Map<String, Object> getCatalogItem(String catalogId) {
    return present(requireItem(catalogId, false), true);
  }

  private Map<String, Object> present(CatalogItem item, boolean detail) {
    var files = catalog.files(item.itemId());
    var value = new LinkedHashMap<String, Object>();
    value.put("catalogId", item.catalogId());
    value.put("brand", "IKEA");
    value.put("article", item.article());
    value.put("name", item.name());
    value.put("category", item.category());
    value.put("dimensions", item.dimensions());
    value.put("sourceUrl", item.sourceUrl());
    value.put("thumbnail", presentation.present(requireFile(files, "THUMBNAIL")));
    if (detail)
      value.put(
          "model",
          item.model(
              presentation.present(requireFile(files, "MODEL")),
              presentation.present(requireFile(files, "COLLISION")),
              item.importedAt()));
    return value;
  }

  public StoredResponse createFurnitureFromCatalog(
      UUID actor, String path, String key, JsonNode input, boolean mobile) {
    var response =
        idempotency.execute(
            actor.toString(),
            "POST",
            path,
            key,
            StrictJson.stringify(input),
            () -> furnitureQuery.requireTenant(actor),
            () -> create(actor, input, mobile));
    var id = UUID.fromString(StrictJson.parse(response.body()).path("furnitureId").asText());
    furnitureQuery.owned(actor, id, false, false);
    return responses.refresh(response);
  }

  private StoredResponse create(UUID actor, JsonNode input, boolean mobile) {
    // The shared lock keeps a reimport from replacing the source asset rows mid-copy.
    var item = requireItem(input.path("catalogId").asText(), true);
    var files = catalog.files(item.itemId());
    UUID id = UUID.randomUUID();
    var now = clock.instant();
    furniture.create(
        id, actor, input.path("name").asText(item.name()), item.category(), item.dimensions(), now);
    var model = copy(requireFile(files, "MODEL"), actor, id, now);
    var collision = copy(requireFile(files, "COLLISION"), actor, id, now);
    var document = item.model(signatures.asset(model), signatures.asset(collision), now);
    models.initialModel(id, StrictJson.parse(StrictJson.stringify(document)));
    var body = FurnitureMapper.response(furnitureQuery.get(actor, id, null), mobile);
    return new StoredResponse(
        201,
        StrictJson.stringify(body),
        Map.of(
            "Location", (mobile ? "/api/furniture/" : "/api/v1/furniture/") + id, "ETag", "\"1\""));
  }

  private AssetFile copy(AssetFile source, UUID owner, UUID furnitureId, Instant now) {
    if (!storage.exists(source.sha256())) throw new ApiException(503, "STORAGE_UNAVAILABLE");
    UUID id = UUID.randomUUID();
    var file =
        new AssetFile(
            id,
            owner,
            null,
            "FURNITURE",
            furnitureId,
            "RESULT",
            id,
            source.name(),
            source.contentType(),
            source.byteSize(),
            source.sha256(),
            source.format(),
            source.role(),
            source.ordinal(),
            true,
            true,
            null,
            now);
    uploads.add(file);
    return file;
  }

  private CatalogItem requireItem(String catalogId, boolean lock) {
    return catalog
        .find(catalogId, lock)
        .orElseThrow(() -> new ApiException(404, "RESOURCE_NOT_FOUND"));
  }

  private AssetFile requireFile(Map<String, AssetFile> files, String role) {
    var file = files.get(role);
    if (file == null || !file.stored() || !file.verified())
      throw new ApiException(503, "STORAGE_UNAVAILABLE");
    return file;
  }
}
