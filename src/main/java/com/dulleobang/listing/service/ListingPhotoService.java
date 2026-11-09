package com.dulleobang.listing.service;

import com.dulleobang.common.exception.ApiException;
import com.dulleobang.common.idempotency.*;
import com.dulleobang.common.idempotency.service.IdempotencyService;
import com.dulleobang.common.json.StrictJson;
import com.dulleobang.listing.repository.ListingRepository;
import com.dulleobang.upload.dto.UploadedFile;
import com.dulleobang.upload.repository.UploadRepository;
import com.dulleobang.upload.service.MultipartStoreService;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class ListingPhotoService {
  private final ListingAccessService access;
  private final ListingRepository listings;
  private final ListingViewService views;
  private final MultipartStoreService store;
  private final UploadRepository uploads;
  private final IdempotencyService idempotency;
  private final Clock clock;
  private final com.dulleobang.upload.service.AssetResponseRefresher responses;

  public ListingPhotoService(
      ListingAccessService access,
      ListingRepository listings,
      ListingViewService views,
      MultipartStoreService store,
      UploadRepository uploads,
      IdempotencyService idempotency,
      Clock clock,
      com.dulleobang.upload.service.AssetResponseRefresher responses) {
    this.access = access;
    this.listings = listings;
    this.views = views;
    this.store = store;
    this.uploads = uploads;
    this.idempotency = idempotency;
    this.clock = clock;
    this.responses = responses;
  }

  public StoredResponse upload(
      UUID actor, UUID id, String path, String key, List<UploadedFile> files) {
    Runnable authorize =
        () -> {
          if (access.get(actor, id, true, false, false).status().equals("ARCHIVED"))
            throw new ApiException(409, "INVALID_STATE");
        };
    authorize.run();
    return responses.refresh(
        idempotency.execute(
            actor.toString(),
            "POST",
            path,
            key,
            StrictJson.stringify(Map.of("files", store.fingerprint(files))),
            authorize,
            () -> {
              access.get(actor, id, true, true, false);
              var sources = store.store(actor, "LISTING_PHOTO", "LISTING", id, files);
              var derived =
                  sources.stream()
                      .map(file -> uploads.asset(file, false).orElseThrow().derivative())
                      .toList();
              listings.replacePhotos(id, sources, derived);
              listings.publication(id, "PUBLISHED", clock.instant());
              var item = access.get(actor, id, true, false, false);
              return new StoredResponse(
                  200,
                  StrictJson.stringify(views.response(actor, item, false, true)),
                  Map.of("ETag", "\"" + item.version() + "\""));
            }));
  }
}
