package com.dulleobang.listing.service;

import com.dulleobang.common.contract.QueryInput;
import com.dulleobang.common.pagination.service.CursorService;
import com.dulleobang.listing.domain.ListingRecord;
import com.dulleobang.listing.dto.ListingPage;
import com.dulleobang.listing.dto.ListingResponse;
import com.dulleobang.listing.mapper.MobileSearchMapper;
import com.dulleobang.listing.repository.ListingRepository;
import com.dulleobang.listing.validation.ListingSearchPolicy;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ListingQueryService {
  public record Versioned<T>(T body, long version) {}

  private final ListingRepository repository;
  private final ListingAccessService access;
  private final ListingViewService views;
  private final ListingSearchPolicy policy;
  private final CursorService cursors;
  private final QueryInput parameters;
  private final MobileSearchMapper mobileSearch;

  public ListingQueryService(
      ListingRepository repository,
      ListingAccessService access,
      ListingViewService views,
      ListingSearchPolicy policy,
      CursorService cursors,
      QueryInput parameters,
      MobileSearchMapper mobileSearch) {
    this.repository = repository;
    this.access = access;
    this.views = views;
    this.policy = policy;
    this.cursors = cursors;
    this.parameters = parameters;
    this.mobileSearch = mobileSearch;
  }

  public Versioned<ListingResponse> getMobile(UUID actor, UUID id) {
    var item = access.get(actor, id, false, false, false);
    return new Versioned<>(views.mobile(actor, item), item.version());
  }

  public Versioned<ObjectNode> getLegacy(UUID actor, UUID id) {
    var item = access.get(actor, id, false, false, false);
    return new Versioned<>(views.canonical(actor, item, false), item.version());
  }

  @Transactional(isolation = Isolation.REPEATABLE_READ)
  public ListingPage<ListingResponse> listMobile(
      UUID actor, Map<String, List<String>> raw, boolean mine) {
    var q = read(mine ? raw : mobileSearch.convert(raw), mine);
    return list(
        actor,
        mine ? "/api/users/me/listings" : "/api/listings",
        q,
        mine,
        !raw.containsKey("limit") && !raw.containsKey("cursor"),
        item -> views.mobile(actor, item));
  }

  @Transactional(isolation = Isolation.REPEATABLE_READ)
  public ListingPage<ObjectNode> listLegacy(
      UUID actor, Map<String, List<String>> raw, boolean mine) {
    return list(
        actor,
        mine ? "/api/v1/users/me/listings" : "/api/v1/listings",
        read(raw, mine),
        mine,
        false,
        item -> views.canonical(actor, item, true));
  }

  private Map<String, Object> read(Map<String, List<String>> raw, boolean mine) {
    var query = parameters.read(mine ? "getMyListings" : "searchListings", raw);
    if (!mine) policy.validate(query);
    return query;
  }

  private <T> ListingPage<T> list(
      UUID actor,
      String path,
      Map<String, Object> q,
      boolean mine,
      boolean aggregate,
      Function<ListingRecord, T> present) {
    UUID owner = mine ? actor : null;
    if (aggregate)
      return new ListingPage.Aggregate<>(
          repository.search(owner, q).stream().map(present).toList(), null);
    var page = cursors.select(actor, path, q, repository.searchIds(owner, q));
    var items = repository.findOrdered(page.ids()).stream().map(present).toList();
    return new ListingPage.Paginated<>(
        items, new ListingPage.PageInfo(page.nextCursor(), page.hasNext()));
  }
}
