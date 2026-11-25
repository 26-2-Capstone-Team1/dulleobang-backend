package com.dulleobang.user.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
public class UserEntity {
  @Id
  @Column(name = "user_id")
  private UUID id;

  @Column(name = "display_name", nullable = false, length = 80)
  private String displayName;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected UserEntity() {}

  public UserEntity(UUID id, String displayName, Instant createdAt) {
    this.id = id;
    this.displayName = displayName;
    this.createdAt = createdAt;
  }

  public UUID getId() {
    return id;
  }

  public String getDisplayName() {
    return displayName;
  }
}
