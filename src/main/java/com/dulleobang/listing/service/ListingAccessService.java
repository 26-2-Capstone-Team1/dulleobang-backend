package com.dulleobang.listing.service;

import com.dulleobang.common.exception.ApiException;
import com.dulleobang.listing.domain.ListingRecord;
import com.dulleobang.listing.repository.ListingRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ListingAccessService {
  private final ListingRepository repository;

  public ListingAccessService(ListingRepository repository) {
    this.repository = repository;
  }

  public ListingRecord get(UUID actor, UUID id, boolean own, boolean lock, boolean deleted) {
    var listing =
        repository.find(id, lock).orElseThrow(() -> new ApiException(404, "RESOURCE_NOT_FOUND"));
    if ((listing.deleted() && !deleted)
        || (own
            ? !listing.owner().equals(actor)
            : !listing.owner().equals(actor) && !listing.status().equals("PUBLISHED")))
      throw new ApiException(404, "RESOURCE_NOT_FOUND");
    return listing;
  }
}
