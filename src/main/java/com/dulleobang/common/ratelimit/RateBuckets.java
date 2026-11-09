package com.dulleobang.common.ratelimit;

import java.util.*;

final class RateBuckets {
  private RateBuckets() {}

  static Map<String, Integer> privateRequest(String actor, String operation, String path) {
    if (path.startsWith("/api/v1/internal/")) return Map.of("worker:" + actor, 600);
    var result = new LinkedHashMap<String, Integer>();
    result.put("user:" + actor, 120);
    if (Set.of(
            "createUploadSession",
            "renewUploadUrls",
            "getAssetDownload",
            "uploadListingImages",
            "applyAgentVerification",
            "importSam3dFurniture")
        .contains(operation)) result.put("urls:" + actor, 20);
    if (Set.of(
            "submitRoomCapture",
            "submitRoomCaptureZip",
            "submitFurnitureCapture",
            "submitFurnitureCaptureZip",
            "createRoomState",
            "retryJob",
            "importSam3dFurniture")
        .contains(operation)) result.put("ai:" + actor, 10);
    if (operation.equals("getJob"))
      result.put(
          "poll:"
              + actor
              + ":"
              + path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT),
          20);
    return result;
  }
}
