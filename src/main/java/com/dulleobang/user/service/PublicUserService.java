package com.dulleobang.user.service;

import com.dulleobang.verification.repository.AgentVerificationRepository;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class PublicUserService {
  private final UserService users;
  private final AgentVerificationRepository agents;

  public PublicUserService(UserService users, AgentVerificationRepository agents) {
    this.users = users;
    this.agents = agents;
  }

  public Map<String, Object> view(UUID id, boolean summary) {
    var user = users.get(id);
    var approved = agents.approved(id);
    boolean verified = user.roles().contains("LISTING_AGENT");
    var result = new LinkedHashMap<String, Object>();
    result.put("userId", id);
    result.put(
        "displayName",
        verified && approved.isPresent() ? approved.get().name() : user.displayName());
    result.put("isVerifiedListingAgent", verified);
    if (!summary) {
      result.put(
          "officeName", verified && approved.isPresent() ? approved.get().officeName() : null);
      result.put(
          "phone",
          verified && approved.isPresent()
              ? approved.get().phone()
              : user.isPhonePublic() ? user.phone() : null);
    }
    return result;
  }
}
