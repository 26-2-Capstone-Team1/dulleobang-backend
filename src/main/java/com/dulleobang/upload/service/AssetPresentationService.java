package com.dulleobang.upload.service;

import com.dulleobang.common.exception.ApiException;
import com.dulleobang.upload.repository.UploadRepository;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class AssetPresentationService {
  private final UploadRepository repository;
  private final SignedUrlService signatures;

  public AssetPresentationService(UploadRepository repository, SignedUrlService signatures) {
    this.repository = repository;
    this.signatures = signatures;
  }

  /** Call only after the containing resource's access policy has succeeded. */
  public Map<String, Object> present(UUID id) {
    var file =
        repository.asset(id, false).orElseThrow(() -> new ApiException(404, "RESOURCE_NOT_FOUND"));
    if (!file.verified() || !file.stored()) throw new ApiException(404, "RESOURCE_NOT_FOUND");
    var grant = signatures.grant(file, "GET");
    var output = new LinkedHashMap<>(signatures.asset(file));
    output.put("url", grant.get("url"));
    output.put("urlExpiresAt", grant.get("expiresAt"));
    return output;
  }
}
