package com.dulleobang.chat.service;

import com.dulleobang.chat.domain.ChatRoom;
import com.dulleobang.chat.repository.ChatRepository;
import com.dulleobang.common.exception.ApiException;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ChatAccessService {
  private final ChatRepository repository;

  public ChatAccessService(ChatRepository repository) {
    this.repository = repository;
  }

  public ChatRoom owned(UUID actor, UUID chat, boolean lock) {
    var room =
        repository.find(chat, lock).orElseThrow(() -> new ApiException(404, "RESOURCE_NOT_FOUND"));
    if (!room.owner().equals(actor) && !room.inquirer().equals(actor))
      throw new ApiException(404, "RESOURCE_NOT_FOUND");
    return room;
  }
}
