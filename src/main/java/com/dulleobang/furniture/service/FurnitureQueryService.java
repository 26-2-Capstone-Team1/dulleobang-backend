package com.dulleobang.furniture.service;

import com.dulleobang.common.exception.ApiException;
import com.dulleobang.common.pagination.service.CursorService;
import com.dulleobang.furniture.domain.FurnitureItem;
import com.dulleobang.furniture.mapper.FurnitureMapper;
import com.dulleobang.furniture.repository.FurnitureRepository;
import com.dulleobang.user.service.UserService;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class FurnitureQueryService {
  private final FurnitureRepository repository;
  private final CursorService cursors;
  private final UserService users;
  private final com.dulleobang.space.service.BundlePresentationService bundles;

  public FurnitureQueryService(
      FurnitureRepository repository,
      CursorService cursors,
      UserService users,
      com.dulleobang.space.service.BundlePresentationService bundles) {
    this.repository = repository;
    this.cursors = cursors;
    this.users = users;
    this.bundles = bundles;
  }

  private FurnitureItem present(FurnitureItem item) {
    if (item.model() == null) return item;
    return new FurnitureItem(
        item.id(),
        item.owner(),
        item.name(),
        item.category(),
        item.measuredDimensions(),
        item.status(),
        item.version(),
        bundles.present(item.model()),
        item.activeJob(),
        item.createdAt(),
        item.updatedAt(),
        item.deletedAt());
  }

  public void requireTenant(UUID actor) {
    if (!users.get(actor).roles().contains("TENANT")) throw new ApiException(403, "FORBIDDEN");
  }

  public FurnitureItem owned(UUID actor, UUID id, boolean lock, boolean deleted) {
    var item =
        repository
            .find(id, actor, lock)
            .orElseThrow(() -> new ApiException(404, "RESOURCE_NOT_FOUND"));
    if (!deleted && item.deletedAt() != null) throw new ApiException(404, "RESOURCE_NOT_FOUND");
    return item;
  }

  public FurnitureItem get(UUID actor, UUID id, Integer version) {
    var item = owned(actor, id, false, false);
    if (version == null) return present(item);
    var model =
        repository
            .model(id, version)
            .orElseThrow(() -> new ApiException(404, "RESOURCE_NOT_FOUND"));
    return new FurnitureItem(
        item.id(),
        item.owner(),
        item.name(),
        item.category(),
        item.measuredDimensions(),
        item.status(),
        item.version(),
        bundles.present(model),
        item.activeJob(),
        item.createdAt(),
        item.updatedAt(),
        item.deletedAt());
  }

  public Map<String, Object> all(UUID actor, String status) {
    requireTenant(actor);
    if (status != null && !Set.of("DRAFT", "PROCESSING", "READY", "FAILED").contains(status))
      throw new ApiException(400, "INVALID_QUERY_PARAMETER");
    var result = new LinkedHashMap<String, Object>();
    result.put(
        "items",
        repository.list(actor, status).stream()
            .map(x -> FurnitureMapper.response(present(x), true))
            .toList());
    result.put("nextCursor", null);
    return result;
  }

  public Map<String, Object> list(
      UUID actor, String path, boolean mobile, String status, int limit, String cursor) {
    requireTenant(actor);
    if (limit < 1
        || limit > 50
        || (status != null && !Set.of("DRAFT", "PROCESSING", "READY", "FAILED").contains(status)))
      throw new ApiException(422, "VALIDATION_FAILED");
    var values = repository.list(actor, status);
    var filters = new LinkedHashMap<String, Object>();
    filters.put("limit", limit);
    if (status != null) filters.put("status", status);
    Map<String, FurnitureItem> live = new HashMap<>();
    values.forEach(x -> live.put(x.id().toString(), x));
    var page =
        cursor == null
            ? cursors.start(
                actor, path, filters, values.stream().map(x -> x.id().toString()).toList(), limit)
            : cursors.resume(actor, path, filters, cursor, limit, live::containsKey);
    var items =
        page.ids().stream()
            .filter(live::containsKey)
            .map(id -> FurnitureMapper.response(present(live.get(id)), mobile))
            .toList();
    var pageInfo = new LinkedHashMap<String, Object>();
    pageInfo.put("nextCursor", page.nextCursor());
    pageInfo.put("hasNext", page.hasNext());
    return Map.of("items", items, "page", pageInfo);
  }
}
