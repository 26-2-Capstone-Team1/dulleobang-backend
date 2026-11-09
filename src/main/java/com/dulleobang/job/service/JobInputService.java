package com.dulleobang.job.service;

import com.dulleobang.capture.repository.CaptureRepository;
import com.dulleobang.furniture.repository.FurnitureRepository;
import com.dulleobang.job.domain.JobRecord;
import com.dulleobang.job.repository.JobInputRepository;
import com.dulleobang.upload.repository.UploadRepository;
import com.dulleobang.upload.service.SignedUrlService;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class JobInputService {
  private final JobInputRepository inputs;
  private final CaptureRepository captures;
  private final UploadRepository uploads;
  private final SignedUrlService signatures;
  private final FurnitureRepository furniture;

  public JobInputService(
      JobInputRepository inputs,
      CaptureRepository captures,
      UploadRepository uploads,
      SignedUrlService signatures,
      FurnitureRepository furniture) {
    this.inputs = inputs;
    this.captures = captures;
    this.uploads = uploads;
    this.signatures = signatures;
    this.furniture = furniture;
  }

  private Map<String, Object> download(UUID id) {
    return signatures.grant(uploads.asset(id, false).orElseThrow(), "GET");
  }

  public Map<String, Object> input(JobRecord job) {
    return job.type().equals("ROOM_OBJECT_REMOVAL") ? removal(job) : reconstruction(job);
  }

  private Map<String, Object> removal(JobRecord job) {
    var result = new LinkedHashMap<String, Object>();
    result.putAll(inputs.removal(job.targetId()));
    var source = inputs.sourceBundle(job.targetId());
    result.put("kind", "REMOVAL");
    result.put("sourceBundle", source);
    if (source.has("meshSource")) {
      var meshes = new ArrayList<Map<String, Object>>();
      meshes.add(
          download(
              UUID.fromString(
                  source.path("meshSource").path("roomAsset").path("fileId").asText())));
      meshes.add(
          download(
              UUID.fromString(
                  source.path("meshSource").path("roomCollisionAsset").path("fileId").asText())));
      for (var object : source.path("meshSource").path("objectAssets"))
        meshes.add(download(UUID.fromString(object.path("asset").path("fileId").asText())));
      result.put("meshDownloads", meshes);
    }
    result.put(
        "sourceDownloads",
        List.of("sceneAsset", "geometryAsset", "collisionAsset").stream()
            .map(key -> download(UUID.fromString(source.path(key).path("fileId").asText())))
            .toList());
    return result;
  }

  private Map<String, Object> reconstruction(JobRecord job) {
    var result = new LinkedHashMap<String, Object>();
    var capture = captures.find(job.captureId()).orElseThrow();
    var files = uploads.files(capture.session());
    result.put("kind", "RECONSTRUCTION");
    result.put("captureId", capture.id());
    for (var pair :
        Map.of(
                "CAMERA_MANIFEST",
                "manifest",
                "IMAGE",
                "images",
                "DEPTH",
                "depths",
                "CONFIDENCE",
                "confidences",
                "ROOMPLAN",
                "roomplan",
                "ROOMPLAN_STRUCTURE",
                "roomplanStructure")
            .entrySet()) {
      var grants =
          files.stream()
              .filter(f -> f.role().equals(pair.getKey()))
              .map(f -> download(f.id()))
              .toList();
      result.put(
          pair.getValue(),
          Set.of("manifest", "roomplan", "roomplanStructure").contains(pair.getValue())
              ? (grants.isEmpty() ? null : grants.get(0))
              : grants);
    }
    result.put(
        "measuredDimensions",
        job.targetType().equals("FURNITURE")
            ? furniture.find(job.targetId(), job.owner(), false).orElseThrow().measuredDimensions()
            : null);
    if (capture.archive() != null) result.put("archive", download(capture.archive()));
    return result;
  }
}
