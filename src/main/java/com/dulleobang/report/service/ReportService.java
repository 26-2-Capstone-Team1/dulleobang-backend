package com.dulleobang.report.service;

import com.dulleobang.common.exception.ApiException;
import com.dulleobang.common.idempotency.*;
import com.dulleobang.common.idempotency.service.IdempotencyService;
import com.dulleobang.common.json.StrictJson;
import com.dulleobang.listing.service.ListingAccessService;
import com.dulleobang.report.repository.ReportRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class ReportService {
  private final ReportRepository repository;
  private final ListingAccessService listings;
  private final IdempotencyService idempotency;
  private final Clock clock;

  public ReportService(
      ReportRepository repository,
      ListingAccessService listings,
      IdempotencyService idempotency,
      Clock clock) {
    this.repository = repository;
    this.listings = listings;
    this.idempotency = idempotency;
    this.clock = clock;
  }

  public StoredResponse create(UUID actor, UUID listing, String path, String key, JsonNode input) {
    Runnable authorize =
        () -> {
          if (listings.get(actor, listing, false, false, false).owner().equals(actor))
            throw new ApiException(403, "SELF_REPORT_FORBIDDEN");
        };
    authorize.run();
    return idempotency.execute(
        actor.toString(),
        "POST",
        path,
        key,
        StrictJson.stringify(input),
        authorize,
        () -> {
          repository.lock(actor, listing);
          var recent = repository.recent(actor, listing, clock.instant().minusSeconds(86400));
          UUID id = recent.orElseGet(UUID::randomUUID);
          if (recent.isEmpty())
            repository.create(
                id,
                actor,
                listing,
                input.path("reason").asText(),
                input.path("detail").asText(null),
                clock.instant());
          return new StoredResponse(
              recent.isPresent() ? 200 : 201,
              StrictJson.stringify(Map.of("reportId", id)),
              Map.of());
        });
  }
}
