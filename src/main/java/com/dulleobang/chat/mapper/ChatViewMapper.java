package com.dulleobang.chat.mapper;

import com.dulleobang.chat.domain.ChatMessage;
import com.dulleobang.chat.dto.ChatView;
import com.dulleobang.common.json.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class ChatViewMapper {
  public Map<String, Object> message(ChatMessage message, boolean mobile, String name) {
    var value = new LinkedHashMap<String, Object>();
    value.put("messageId", message.id());
    value.put("chatId", message.chat());
    value.put("senderId", message.sender());
    value.put("text", message.text());
    value.put("sentAt", ApiTime.format(message.sentAt()));
    if (mobile) {
      value.put("message", message.text());
      value.put("senderName", name);
    }
    return value;
  }

  public ObjectNode room(
      UUID actor,
      ChatView view,
      boolean summary,
      boolean mobile,
      List<Map<String, Object>> rendered) {
    var room = view.room();
    var last = view.messages().isEmpty() ? null : view.messages().get(0);
    var value = new LinkedHashMap<String, Object>();
    value.put("chatId", room.id());
    value.put("listing", view.listing());
    value.put("counterpart", view.counterpart());
    value.put("lastMessage", last == null ? null : last.text());
    value.put("lastMessageAt", last == null ? null : ApiTime.format(last.sentAt()));
    value.put(
        "unreadCount",
        view.messages().stream()
            .filter(m -> !m.sender().equals(actor) && m.sequence() > view.readMark())
            .count());
    if (!summary) {
      value.put("inquirerId", room.inquirer());
      value.put("ownerId", room.owner());
      value.put("createdAt", ApiTime.format(room.createdAt()));
      value.put("readOnly", !view.live());
    }
    if (mobile) {
      value.put("counterpartName", view.counterpart().get("displayName"));
      value.put("officeName", view.counterpart().get("officeName"));
      if (!summary) value.put("messages", rendered);
    }
    return (ObjectNode) StrictJson.parse(StrictJson.stringify(value));
  }
}
