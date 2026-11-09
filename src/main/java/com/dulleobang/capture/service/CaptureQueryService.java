package com.dulleobang.capture.service;

import com.dulleobang.capture.repository.CaptureRepository;
import com.dulleobang.common.exception.ApiException;
import com.dulleobang.common.json.ApiTime;
import com.dulleobang.job.repository.JobRepository;
import com.dulleobang.upload.repository.UploadRepository;
import com.dulleobang.upload.service.*;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class CaptureQueryService {
  private final CaptureRepository captures;
  private final UploadRepository uploads;
  private final UploadAccessService access;
  private final JobRepository jobs;
  private final AssetPresentationService assets;
  private final Clock clock;

  public CaptureQueryService(
      CaptureRepository captures,
      UploadRepository uploads,
      UploadAccessService access,
      JobRepository jobs,
      AssetPresentationService assets,
      Clock clock) {
    this.captures = captures;
    this.uploads = uploads;
    this.access = access;
    this.jobs = jobs;
    this.assets = assets;
    this.clock = clock;
  }

  public Map<String, Object> get(UUID actor, UUID id) {
    var c = captures.find(id).orElseThrow(() -> new ApiException(404, "RESOURCE_NOT_FOUND"));
    if (!c.owner().equals(actor)) throw new ApiException(404, "RESOURCE_NOT_FOUND");
    access.live(c.targetType(), c.targetId());
    var latest = jobs.find(c.latestJob(), false).orElseThrow();
    if (!latest.active() && !latest.updatedAt().plusSeconds(30L * 86400).isAfter(clock.instant()))
      throw new ApiException(410, "INPUT_EXPIRED");
    var files = uploads.files(c.session());
    var response = new LinkedHashMap<String, Object>();
    response.put("captureId", c.id());
    response.put("targetType", c.targetType());
    response.put("targetId", c.targetId());
    response.put("uploadSessionId", c.session());
    response.put(
        "manifestAsset",
        assets.present(
            files.stream()
                .filter(f -> f.role().equals("CAMERA_MANIFEST"))
                .findFirst()
                .orElseThrow()
                .id()));
    response.put(
        "imageAssets",
        files.stream()
            .filter(f -> f.role().equals("IMAGE"))
            .map(f -> assets.present(f.id()))
            .toList());
    response.put(
        "supplementalAssets",
        files.stream()
            .filter(f -> !Set.of("IMAGE", "CAMERA_MANIFEST").contains(f.role()))
            .map(
                f ->
                    Map.of(
                        "clientFileId",
                        f.clientId(),
                        "role",
                        f.role(),
                        "asset",
                        assets.present(f.id())))
            .toList());
    if (c.archive() != null) response.put("archiveAsset", assets.present(c.archive()));
    response.put("latestJobId", c.latestJob());
    response.put("createdAt", ApiTime.format(c.createdAt()));
    return response;
  }
}
