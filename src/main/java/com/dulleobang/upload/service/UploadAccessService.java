package com.dulleobang.upload.service;

import com.dulleobang.common.exception.ApiException;
import com.dulleobang.furniture.service.FurnitureQueryService;
import com.dulleobang.upload.repository.UploadRepository;
import com.dulleobang.user.service.UserService;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class UploadAccessService {
  private final UploadRepository repository;
  private final FurnitureQueryService furniture;
  private final UserService users;

  public UploadAccessService(
      UploadRepository repository, FurnitureQueryService furniture, UserService users) {
    this.repository = repository;
    this.furniture = furniture;
    this.users = users;
  }

  public void owned(UUID actor, String type, UUID id) {
    if (type.equals("FURNITURE")) {
      furniture.requireTenant(actor);
      furniture.owned(actor, id, false, false);
    } else if (type.equals("USER")) {
      if (!actor.equals(id)) throw missing();
      users.get(actor);
    } else if (!repository.listingOwned(id, actor, false)) throw missing();
    else if (!repository.listingOwned(id, actor, true))
      throw new ApiException(409, "INVALID_STATE");
  }

  public void live(String type, UUID id) {
    boolean live =
        type.equals("ROOM_STATE")
            ? repository.stateLive(id)
            : type.equals("FURNITURE")
                ? repository.furnitureLive(id)
                : type.equals("LISTING") ? repository.listingLive(id) : true;
    if (!live) throw missing();
  }

  private ApiException missing() {
    return new ApiException(404, "RESOURCE_NOT_FOUND");
  }
}
