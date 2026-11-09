package com.dulleobang.chat.service;

import com.dulleobang.chat.domain.ChatRoom;
import com.dulleobang.chat.repository.ChatRepository;
import com.dulleobang.common.exception.ApiException;
import com.dulleobang.common.idempotency.*;
import com.dulleobang.common.idempotency.service.IdempotencyService;
import com.dulleobang.common.json.StrictJson;
import com.dulleobang.common.pagination.service.CollectionPageService;
import com.dulleobang.listing.service.ListingAccessService;
import com.dulleobang.listing.service.ListingViewService;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class ChatRoomService {
  private final ChatRepository repository;
  private final ListingAccessService listings;
  private final ListingViewService listingViews;
  private final ChatViewService views;
  private final IdempotencyService idempotency;
  private final CollectionPageService pages;
  private final Clock clock;
  private final com.dulleobang.upload.service.AssetResponseRefresher responses;

  public ChatRoomService(
      ChatRepository repository,
      ListingAccessService listings,
      ListingViewService listingViews,
      ChatViewService views,
      IdempotencyService idempotency,
      CollectionPageService pages,
      Clock clock,
      com.dulleobang.upload.service.AssetResponseRefresher responses) {
    this.repository = repository;
    this.listings = listings;
    this.listingViews = listingViews;
    this.views = views;
    this.idempotency = idempotency;
    this.pages = pages;
    this.clock = clock;
    this.responses = responses;
  }

  public StoredResponse create(UUID actor, UUID listing, String path, String key, boolean mobile) {
    return responses.refresh(
        idempotency.execute(
            actor.toString(),
            "POST",
            path,
            key,
            StrictJson.stringify(Map.of("listingId", listing)),
            () -> listings.get(actor, listing, false, false, false),
            () -> {
              var item = listings.get(actor, listing, false, true, false);
              if (item.owner().equals(actor)) throw new ApiException(409, "SELF_CHAT_FORBIDDEN");
              if (!item.status().equals("PUBLISHED"))
                throw new ApiException(404, "RESOURCE_NOT_FOUND");
              var existing = repository.existing(listing, actor);
              ChatRoom room =
                  existing.orElseGet(
                      () -> {
                        var now = clock.instant();
                        var r =
                            new ChatRoom(
                                UUID.randomUUID(),
                                listing,
                                actor,
                                item.owner(),
                                listingViews.canonical(actor, item, true),
                                now,
                                now);
                        repository.create(r);
                        return r;
                      });
              return new StoredResponse(
                  existing.isPresent() ? 200 : 201,
                  StrictJson.stringify(views.room(actor, room, false, mobile)),
                  Map.of("Location", "/api/v1/chats/" + room.id()));
            }));
  }

  public Map<String, Object> list(
      UUID actor, String path, Map<String, Object> q, boolean mobile, boolean aggregate) {
    return pages.page(
        actor,
        path,
        q,
        repository.rooms(actor),
        r -> r.id().toString(),
        r -> views.room(actor, r, true, mobile),
        aggregate);
  }
}
