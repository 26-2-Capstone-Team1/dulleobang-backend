package com.dulleobang.upload.service;

import com.dulleobang.capture.validation.*;
import com.dulleobang.common.exception.ApiException;
import com.dulleobang.infrastructure.storage.AssetStorage;
import com.dulleobang.upload.domain.AssetFile;
import com.dulleobang.upload.validation.ImageValidator;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class UploadVerifyService {
  private final ImageValidator images;
  private final CaptureJsonValidator json;
  private final CaptureManifestValidator manifests;
  private final AssetStorage storage;

  public UploadVerifyService(
      ImageValidator images,
      CaptureJsonValidator json,
      CaptureManifestValidator manifests,
      AssetStorage storage) {
    this.images = images;
    this.json = json;
    this.manifests = manifests;
    this.storage = storage;
  }

  public JsonNode verify(List<AssetFile> files) {
    var sizes = new HashMap<String, ImageValidator.Size>();
    JsonNode manifest = null;
    for (var file : files) {
      if (!file.stored()) throw new ApiException(409, "UPLOAD_INCOMPLETE");
      byte[] raw = storage.read(file.sha256(), file.byteSize());
      switch (file.role()) {
        case "IMAGE", "PHOTO", "LISTING_AGENT_DOCUMENT" ->
            sizes.put(file.clientId().toString(), images.validate(raw, file.contentType()));
        case "DEPTH", "CONFIDENCE" ->
            sizes.put(
                file.clientId().toString(), images.depth(raw, file.role().equals("CONFIDENCE")));
        default -> {
          var value = json.read(raw, file.role());
          if (file.role().equals("CAMERA_MANIFEST")) manifest = value;
        }
      }
    }
    if (manifest != null) manifests.validate(manifest, files, sizes);
    return manifest;
  }
}
