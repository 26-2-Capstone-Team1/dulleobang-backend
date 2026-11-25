package com.dulleobang.common.pagination.service;

import java.util.*;
import java.util.function.Function;
import org.springframework.stereotype.Service;

@Service
public class CollectionPageService {
  private final CursorService cursors;

  public CollectionPageService(CursorService cursors) {
    this.cursors = cursors;
  }

  public <T> Map<String, Object> page(
      UUID actor,
      String path,
      Map<String, Object> query,
      List<T> current,
      Function<T, String> identifier,
      Function<T, Object> mapper,
      boolean aggregate) {
    if (aggregate) {
      var result = new LinkedHashMap<String, Object>();
      result.put("items", current.stream().map(mapper).toList());
      result.put("nextCursor", null);
      return result;
    }
    var filters = new LinkedHashMap<>(query);
    String token = (String) filters.remove("cursor");
    int limit = ((Number) filters.getOrDefault("limit", 20)).intValue();
    var live = new LinkedHashMap<String, T>();
    current.forEach(item -> live.put(identifier.apply(item), item));
    var page =
        token == null
            ? cursors.start(actor, path, filters, new ArrayList<>(live.keySet()), limit)
            : cursors.resume(actor, path, filters, token, limit, live::containsKey);
    var info = new LinkedHashMap<String, Object>();
    info.put("nextCursor", page.nextCursor());
    info.put("hasNext", page.hasNext());
    return Map.of(
        "items",
        page.ids().stream()
            .filter(live::containsKey)
            .map(id -> mapper.apply(live.get(id)))
            .toList(),
        "page",
        info);
  }
}
