package com.dulleobang.upload.controller;

import com.dulleobang.upload.service.UploadSessionService;
import java.security.Principal;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class AssetController {
  private final UploadSessionService uploads;

  public AssetController(UploadSessionService uploads) {
    this.uploads = uploads;
  }

  @GetMapping("/api/v1/assets/{id}/download")
  public Map<String, Object> download(Principal actor, @PathVariable UUID id) {
    return uploads.download(UUID.fromString(actor.getName()), id);
  }
}
