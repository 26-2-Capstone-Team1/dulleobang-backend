package com.dulleobang.listing.service;

import com.dulleobang.common.pagination.service.CollectionPageService;
import com.dulleobang.listing.repository.ListingRepository;
import com.dulleobang.listing.validation.ListingSearchPolicy;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class ListingQueryService {
  private final ListingRepository repository;
  private final ListingViewService views;
  private final ListingSearchPolicy policy;
  private final CollectionPageService pages;

  public ListingQueryService(
      ListingRepository repository,
      ListingViewService views,
      ListingSearchPolicy policy,
      CollectionPageService pages) {
    this.repository = repository;
    this.views = views;
    this.policy = policy;
    this.pages = pages;
  }

  public Map<String, Object> list(
      UUID actor,
      String path,
      Map<String, Object> q,
      boolean mine,
      boolean mobile,
      boolean aggregate) {
    if (!mine) policy.validate(q);
    var values =
        repository.all().stream()
            .filter(
                x ->
                    mine
                        ? x.owner().equals(actor)
                            && (!q.containsKey("status") || x.status().equals(q.get("status")))
                        : x.status().equals("PUBLISHED") && policy.matches(x, q))
            .sorted(policy.order(mine ? "LATEST" : q.getOrDefault("sort", "LATEST").toString()))
            .toList();
    return pages.page(
        actor,
        path,
        q,
        values,
        x -> x.id().toString(),
        x -> views.response(actor, x, true, mobile),
        aggregate);
  }
}
