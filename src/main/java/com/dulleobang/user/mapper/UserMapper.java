package com.dulleobang.user.mapper;

import com.dulleobang.user.domain.UserAccount;
import com.dulleobang.user.dto.UserResponse;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

public final class UserMapper {
  private static final DateTimeFormatter TIME =
      DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

  private UserMapper() {}

  public static UserResponse response(UserAccount user) {
    return new UserResponse(
        user.userId(),
        user.email(),
        user.displayName(),
        user.roles(),
        user.version(),
        TIME.format(user.createdAt()),
        user.authProviders(),
        user.phone(),
        user.isPhonePublic());
  }
}
