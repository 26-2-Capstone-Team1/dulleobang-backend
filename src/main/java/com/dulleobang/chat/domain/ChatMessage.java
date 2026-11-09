package com.dulleobang.chat.domain;

import java.time.Instant;
import java.util.UUID;

public record ChatMessage(
    UUID id, UUID chat, UUID sender, String text, long sequence, Instant sentAt) {}
