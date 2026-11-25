package com.dulleobang.user.service;

import com.dulleobang.common.exception.ApiException;
import com.dulleobang.common.version.ETagParser;
import com.dulleobang.user.domain.UserAccount;
import com.dulleobang.user.repository.UserAccountRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {
  private final UserAccountRepository users;

  public UserService(UserAccountRepository users) {
    this.users = users;
  }

  public UserAccount get(UUID user) {
    return users.find(user).orElseThrow(() -> new ApiException(401, "UNAUTHORIZED"));
  }

  @Transactional
  public UserAccount patch(UUID user, String etag, JsonNode input) {
    var current = get(user);
    ETagParser.requireMatch(etag, current.version());
    String name =
        input.has("displayName") ? input.path("displayName").asText() : current.displayName();
    String phone =
        input.has("phone")
            ? (input.path("phone").isNull() ? null : input.path("phone").asText())
            : current.phone();
    boolean visible =
        input.has("isPhonePublic")
            ? input.path("isPhonePublic").asBoolean()
            : current.isPhonePublic();
    if (!users.patch(user, current.version(), name, phone, visible))
      throw new ApiException(412, "PRECONDITION_FAILED");
    return get(user);
  }
}
