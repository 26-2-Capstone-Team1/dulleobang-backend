package com.dulleobang.chat.service;

import com.dulleobang.chat.repository.ChatRepository;
import com.dulleobang.common.exception.ApiException;
import com.dulleobang.common.idempotency.*;
import com.dulleobang.common.idempotency.service.IdempotencyService;
import com.dulleobang.common.json.StrictJson;
import com.dulleobang.common.pagination.service.CollectionPageService;
import com.dulleobang.listing.repository.ListingRepository;
import com.dulleobang.listing.service.ListingViewService;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChatMessageService {
  private final ChatRepository repository;
  private final ChatAccessService access;
  private final ListingRepository listings;
  private final ListingViewService listingViews;
  private final ChatViewService views;
  private final IdempotencyService idempotency;
  private final CollectionPageService pages;
  private final Clock clock;

  public ChatMessageService(
      ChatRepository repository,
      ChatAccessService access,
      ListingRepository listings,
      ListingViewService listingViews,
      ChatViewService views,
      IdempotencyService idempotency,
      CollectionPageService pages,
      Clock clock) {
    this.repository = repository;
    this.access = access;
    this.listings = listings;
    this.listingViews = listingViews;
    this.views = views;
    this.idempotency = idempotency;
    this.pages = pages;
    this.clock = clock;
  }

  public StoredResponse send(
      UUID actor, UUID id, String path, String key, String text, boolean mobile) {
    access.owned(actor, id, false);
    if (text.isBlank()) throw new ApiException(422, "EMPTY_MESSAGE");
    String body = StrictJson.stringify(Map.of("text", text));
    return idempotency.execute(
        actor.toString(),
        "POST",
        path,
        key,
        body,
        () -> access.owned(actor, id, false),
        () -> {
          var room = access.owned(actor, id, false);
          var listing =
              listings
                  .find(room.listing(), true)
                  .orElseThrow(() -> new ApiException(409, "CHAT_LISTING_UNAVAILABLE"));
          room = access.owned(actor, id, true);
          if (listing.deleted()
              || !listing.status().equals("PUBLISHED")
              || !listing.owner().equals(room.owner()))
            throw new ApiException(409, "CHAT_LISTING_UNAVAILABLE");
          UUID message = UUID.randomUUID();
          repository.add(
              message,
              id,
              actor,
              text,
              clock.instant(),
              listingViews.canonical(actor, listing, true));
          return new StoredResponse(
              201,
              StrictJson.stringify(
                  views.message(repository.message(message).orElseThrow(), mobile)),
              Map.of());
        });
  }

  public Map<String, Object> list(UUID actor, UUID id, String path, Map<String, Object> q) {
    access.owned(actor, id, false);
    long before = Long.MAX_VALUE;
    if (q.containsKey("before")) {
      var message =
          repository
              .message(UUID.fromString(q.get("before").toString()))
              .orElseThrow(() -> new ApiException(404, "RESOURCE_NOT_FOUND"));
      if (!message.chat().equals(id)) throw new ApiException(404, "RESOURCE_NOT_FOUND");
      before = message.sequence();
    }
    long maximum = before;
    var values = repository.messages(id).stream().filter(m -> m.sequence() < maximum).toList();
    return pages.page(
        actor, path, q, values, m -> m.id().toString(), m -> views.message(m, false), false);
  }

  @Transactional
  public com.fasterxml.jackson.databind.node.ObjectNode detail(
      UUID actor, UUID id, boolean mobile) {
    var value = views.room(actor, access.owned(actor, id, mobile), false, mobile);
    var shown = value.path("messages");
    if (mobile && !shown.isEmpty()) {
      UUID last = UUID.fromString(shown.get(shown.size() - 1).path("messageId").asText());
      var message = repository.message(last).orElseThrow();
      repository.mark(id, actor, message.sequence());
    }
    return value;
  }

  @Transactional
  public void read(UUID actor, UUID id, UUID messageId) {
    access.owned(actor, id, true);
    var message =
        repository
            .message(messageId)
            .orElseThrow(() -> new ApiException(404, "RESOURCE_NOT_FOUND"));
    if (!message.chat().equals(id)) throw new ApiException(404, "RESOURCE_NOT_FOUND");
    repository.mark(id, actor, message.sequence());
  }
}
