package com.dulleobang.roomstate.service;

import com.dulleobang.common.exception.ApiException;
import com.dulleobang.listing.service.ListingAccessService;
import com.dulleobang.roomstate.domain.RoomStateRecord;
import com.dulleobang.roomstate.repository.RoomStateRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class RoomStateAccessService {
  private final RoomStateRepository repository;
  private final ListingAccessService listings;

  public RoomStateAccessService(RoomStateRepository repository, ListingAccessService listings) {
    this.repository = repository;
    this.listings = listings;
  }

  public RoomStateRecord get(UUID actor, UUID id, boolean lock, boolean deleted) {
    var state =
        repository.find(id, false).orElseThrow(() -> new ApiException(404, "RESOURCE_NOT_FOUND"));
    if (!state.owner().equals(actor)) throw new ApiException(404, "RESOURCE_NOT_FOUND");
    listings.get(actor, state.listingId(), false, lock, false);
    if (lock)
      state =
          repository.find(id, true).orElseThrow(() -> new ApiException(404, "RESOURCE_NOT_FOUND"));
    if (state.deleted() && !deleted) throw new ApiException(404, "RESOURCE_NOT_FOUND");
    return state;
  }
}
