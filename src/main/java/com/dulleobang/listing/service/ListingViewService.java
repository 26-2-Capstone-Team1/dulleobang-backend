package com.dulleobang.listing.service;

import com.dulleobang.listing.domain.ListingRecord;
import com.dulleobang.listing.mapper.*;
import com.dulleobang.listing.repository.ListingRepository;
import com.dulleobang.upload.service.AssetPresentationService;
import com.dulleobang.user.service.PublicUserService;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ListingViewService {
  private final ListingRepository repository;
  private final PublicUserService users;
  private final AssetPresentationService assets;
  private final ListingViewMapper mapper;
  private final MobileListingMapper mobile;

  public ListingViewService(
      ListingRepository repository,
      PublicUserService users,
      AssetPresentationService assets,
      ListingViewMapper mapper,
      MobileListingMapper mobile) {
    this.repository = repository;
    this.users = users;
    this.assets = assets;
    this.mapper = mapper;
    this.mobile = mobile;
  }

  public ObjectNode canonical(UUID actor, ListingRecord listing, boolean summary) {
    var photos = repository.photoIds(listing.id()).stream().map(assets::present).toList();
    return mapper.canonical(
        actor,
        listing,
        photos,
        users.view(listing.owner(), summary),
        repository.scrapped(actor, listing.id()),
        summary);
  }

  public ObjectNode response(UUID actor, ListingRecord listing, boolean summary, boolean app) {
    return app ? mobile(canonical(actor, listing, false)) : canonical(actor, listing, summary);
  }

  public ObjectNode mobile(ObjectNode value) {
    return mobile.convert(value);
  }
}
