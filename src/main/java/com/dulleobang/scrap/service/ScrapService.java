package com.dulleobang.scrap.service;

import com.dulleobang.common.exception.ApiException;
import com.dulleobang.common.pagination.service.CollectionPageService;
import com.dulleobang.listing.service.ListingAccessService;
import com.dulleobang.listing.service.ListingViewService;
import com.dulleobang.scrap.repository.ScrapRepository;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ScrapService {
  private final ScrapRepository repository;
  private final ListingAccessService listings;
  private final ListingViewService views;
  private final CollectionPageService pages;
  private final Clock clock;

  public ScrapService(
      ScrapRepository repository,
      ListingAccessService listings,
      ListingViewService views,
      CollectionPageService pages,
      Clock clock) {
    this.repository = repository;
    this.listings = listings;
    this.views = views;
    this.pages = pages;
    this.clock = clock;
  }

  @Transactional
  public void save(UUID actor, UUID id) {
    var listing = listings.get(actor, id, false, false, false);
    if (!listing.status().equals("PUBLISHED")) throw new ApiException(404, "RESOURCE_NOT_FOUND");
    repository.save(actor, id, clock.instant());
  }

  @Transactional
  public void delete(UUID actor, UUID id) {
    repository.delete(actor, id);
  }

  public Map<String, Object> list(
      UUID actor, String path, Map<String, Object> q, boolean mobile, boolean aggregate) {
    return pages.page(
        actor,
        path,
        q,
        repository.list(actor),
        UUID::toString,
        id -> views.response(actor, listings.get(actor, id, false, false, false), true, mobile),
        aggregate);
  }
}
