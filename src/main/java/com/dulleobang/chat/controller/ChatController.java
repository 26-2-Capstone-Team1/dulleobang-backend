package com.dulleobang.chat.controller;

import com.dulleobang.chat.service.*;
import com.dulleobang.common.contract.*;
import com.dulleobang.common.contract.HttpResponses;
import com.dulleobang.common.idempotency.service.IdempotencyService;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
public class ChatController {
  private final ChatRoomService rooms;
  private final ChatMessageService messages;
  private final JsonInput input;
  private final QueryInput parameters;

  public ChatController(
      ChatRoomService rooms, ChatMessageService messages, JsonInput input, QueryInput parameters) {
    this.rooms = rooms;
    this.messages = messages;
    this.input = input;
    this.parameters = parameters;
  }

  @PostMapping({"/api/chat", "/api/v1/chats"})
  public ResponseEntity<String> create(
      Principal actor,
      HttpServletRequest request,
      @RequestBody(required = false) byte[] body,
      @RequestHeader(value = "Content-Type", required = false) String type,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    IdempotencyService.requireKey(key);
    return HttpResponses.stored(
        rooms.create(
            UUID.fromString(actor.getName()),
            UUID.fromString(input.read(body, type, "ChatCreate").path("listingId").asText()),
            request.getRequestURI(),
            key,
            mobile(request)));
  }

  @GetMapping({"/api/chat", "/api/v1/chats"})
  public ResponseEntity<String> list(
      Principal actor,
      HttpServletRequest request,
      @RequestParam MultiValueMap<String, String> raw) {
    boolean mobile = mobile(request);
    return HttpResponses.json(
        rooms.list(
            UUID.fromString(actor.getName()),
            request.getRequestURI(),
            parameters.read("getChats", raw),
            mobile,
            mobile && !raw.containsKey("limit") && !raw.containsKey("cursor")));
  }

  @GetMapping({"/api/chat/{id}", "/api/v1/chats/{id}"})
  public ResponseEntity<String> get(
      Principal actor, HttpServletRequest request, @PathVariable UUID id) {
    UUID user = UUID.fromString(actor.getName());
    return HttpResponses.json(messages.detail(user, id, mobile(request)));
  }

  @PostMapping({"/api/chat/{id}", "/api/v1/chats/{id}/messages"})
  public ResponseEntity<String> send(
      Principal actor,
      HttpServletRequest request,
      @PathVariable UUID id,
      @RequestBody(required = false) byte[] body,
      @RequestHeader(value = "Content-Type", required = false) String type,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    IdempotencyService.requireKey(key);
    boolean mobile = mobile(request);
    var value =
        mobile
            ? input.readMobile(body, type, "MobilesendChatMessage")
            : input.read(body, type, "ChatMessageCreate");
    return HttpResponses.stored(
        messages.send(
            UUID.fromString(actor.getName()),
            id,
            request.getRequestURI(),
            key,
            value.path(mobile ? "message" : "text").asText(),
            mobile));
  }

  @GetMapping("/api/v1/chats/{id}/messages")
  public ResponseEntity<String> messages(
      Principal actor,
      HttpServletRequest request,
      @PathVariable UUID id,
      @RequestParam MultiValueMap<String, String> raw) {
    return HttpResponses.json(
        messages.list(
            UUID.fromString(actor.getName()),
            id,
            request.getRequestURI(),
            parameters.read("getChatMessages", raw)));
  }

  @PostMapping("/api/v1/chats/{id}/read")
  public ResponseEntity<Void> read(
      Principal actor,
      @PathVariable UUID id,
      @RequestBody(required = false) byte[] body,
      @RequestHeader(value = "Content-Type", required = false) String type) {
    messages.read(
        UUID.fromString(actor.getName()),
        id,
        UUID.fromString(input.read(body, type, "ChatRead").path("lastReadMessageId").asText()));
    return ResponseEntity.noContent().build();
  }

  private boolean mobile(HttpServletRequest request) {
    return !request.getRequestURI().startsWith("/api/v1/");
  }
}
