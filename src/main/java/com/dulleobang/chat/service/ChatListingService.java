package com.dulleobang.chat.service;

import com.dulleobang.chat.domain.ChatRoom;
import com.dulleobang.common.json.StrictJson;
import com.dulleobang.listing.repository.ListingRepository;
import com.dulleobang.listing.service.ListingViewService;
import com.dulleobang.user.service.PublicUserService;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ChatListingService {
  public record Snapshot(ObjectNode value, boolean live) {}

  private final ListingRepository listings;
  private final ListingViewService views;
  private final PublicUserService users;

  public ChatListingService(
      ListingRepository listings, ListingViewService views, PublicUserService users) {
    this.listings = listings;
    this.views = views;
    this.users = users;
  }

  public Snapshot get(UUID actor, ChatRoom room, boolean mobile) {
    var listing = listings.find(room.listing(), false);
    boolean live =
        listing.isPresent()
            && !listing.get().deleted()
            && listing.get().status().equals("PUBLISHED")
            && listing.get().owner().equals(room.owner());
    var value = live ? views.canonical(actor, listing.get(), true) : room.snapshot().deepCopy();
    if (!live) {
      value.putNull("thumbnail");
      value.put("hasSpace", false);
      value.put(
          "status",
          listing.isPresent() && !listing.get().deleted() ? listing.get().status() : "ARCHIVED");
      value.put("isScrapped", listings.scrapped(actor, room.listing()));
    }
    if (mobile)
      value = live ? views.response(actor, listing.get(), false, true) : archived(value, room);
    return new Snapshot(value, live);
  }

  private ObjectNode archived(ObjectNode snapshot, ChatRoom room) {
    var value = snapshot.deepCopy();
    value.putArray("photos");
    value.putNull("currentSpaceVersion");
    value.putNull("activeJobId");
    value.set("owner", StrictJson.parse(StrictJson.stringify(users.view(room.owner(), false))));
    value.putArray("rentalOptions");
    return views.mobile(value);
  }
}
