package com.dulleobang.upload.controller;

import com.dulleobang.upload.service.StorageObjectService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class StorageObjectController {
  private final StorageObjectService storage;

  public StorageObjectController(StorageObjectService storage) {
    this.storage = storage;
  }

  @PutMapping("/storage/{id}")
  public ResponseEntity<Void> put(
      @PathVariable UUID id,
      @RequestParam(defaultValue = "") String token,
      HttpServletRequest request)
      throws java.io.IOException {
    var file =
        storage.put(
            id,
            token,
            request.getContentType(),
            request.getHeader("x-amz-checksum-sha256"),
            request.getContentLengthLong(),
            request.getInputStream());
    return ResponseEntity.ok().eTag("\"" + file.sha256() + "\"").build();
  }

  @GetMapping("/storage/{id}")
  public ResponseEntity<FileSystemResource> get(
      @PathVariable UUID id, @RequestParam(defaultValue = "") String token) {
    var file = storage.authorize(id, token, "GET", false);
    var path = storage.download(id, token);
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(file.contentType()))
        .header("Content-Disposition", "attachment; filename=" + id)
        .body(new FileSystemResource(path));
  }
}
