package com.dulleobang.chat.dto;

import com.dulleobang.chat.domain.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

public record ChatView(
    ChatRoom room,
    JsonNode listing,
    Map<String, Object> counterpart,
    List<ChatMessage> messages,
    long readMark,
    boolean live) {}
