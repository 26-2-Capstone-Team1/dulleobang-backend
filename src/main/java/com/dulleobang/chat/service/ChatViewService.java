package com.dulleobang.chat.service;

import com.dulleobang.chat.domain.*;
import com.dulleobang.chat.dto.ChatView;
import com.dulleobang.chat.mapper.ChatViewMapper;
import com.dulleobang.chat.repository.ChatRepository;
import com.dulleobang.user.service.PublicUserService;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class ChatViewService {
  private final ChatRepository repository;
  private final ChatListingService listings;
  private final PublicUserService users;
  private final ChatViewMapper mapper;

  public ChatViewService(
      ChatRepository repository,
      ChatListingService listings,
      PublicUserService users,
      ChatViewMapper mapper) {
    this.repository = repository;
    this.listings = listings;
    this.users = users;
    this.mapper = mapper;
  }

  public Map<String, Object> message(ChatMessage message, boolean mobile) {
    String name = mobile ? (String) users.view(message.sender(), true).get("displayName") : null;
    return mapper.message(message, mobile, name);
  }

  public ObjectNode room(UUID actor, ChatRoom room, boolean summary, boolean mobile) {
    var listing = listings.get(actor, room, mobile);
    UUID counterpart = actor.equals(room.inquirer()) ? room.owner() : room.inquirer();
    var user = new LinkedHashMap<>(users.view(counterpart, false));
    user.remove("phone");
    var messages = repository.messages(room.id());
    var view =
        new ChatView(
            room,
            listing.value(),
            user,
            messages,
            repository.readMark(room.id(), actor),
            listing.live());
    List<Map<String, Object>> rendered = List.of();
    if (mobile && !summary) {
      var ordered = new ArrayList<>(messages);
      Collections.reverse(ordered);
      rendered = ordered.stream().map(m -> message(m, true)).toList();
    }
    return mapper.room(actor, view, summary, mobile, rendered);
  }
}
