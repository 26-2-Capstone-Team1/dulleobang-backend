package com.dulleobang.upload.service;

import com.dulleobang.common.exception.ApiException;
import com.dulleobang.common.idempotency.StoredResponse;
import com.dulleobang.common.json.StrictJson;
import com.dulleobang.upload.domain.AssetFile;
import com.dulleobang.upload.repository.UploadRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UploadGrantService {
  private final UploadRepository repository;
  private final UploadQueryService query;
  private final SignedUrlService signatures;

  public UploadGrantService(
      UploadRepository repository, UploadQueryService query, SignedUrlService signatures) {
    this.repository = repository;
    this.query = query;
    this.signatures = signatures;
  }

  public List<Map<String, Object>> grants(UUID id) {
    return repository.files(id).stream().map(f -> signatures.grant(f, "PUT")).toList();
  }

  public StoredResponse refresh(StoredResponse response) {
    var body = (com.fasterxml.jackson.databind.node.ObjectNode) StrictJson.parse(response.body());
    UUID id = UUID.fromString(body.path("session").path("uploadSessionId").asText());
    body.set("uploads", StrictJson.parse(StrictJson.stringify(grants(id))));
    return new StoredResponse(response.status(), StrictJson.stringify(body), response.headers());
  }

  @Transactional
  public Map<String, Object> renew(UUID actor, UUID id, JsonNode input) {
    var session = query.owned(actor, id, true, true);
    if (!session.status().equals("OPEN")) throw new ApiException(409, "INVALID_STATE");
    Map<String, AssetFile> files = new HashMap<>();
    repository.files(id).forEach(f -> files.put(f.clientId().toString(), f));
    var grants = new ArrayList<Map<String, Object>>();
    var seen = new HashSet<String>();
    for (var requested : input.path("clientFileIds")) {
      String client = requested.asText();
      if (!seen.add(client)) throw new ApiException(422, "DUPLICATE_ID");
      if (!files.containsKey(client)) throw new ApiException(422, "UNKNOWN_FILE");
      grants.add(signatures.grant(files.get(client), "PUT"));
    }
    return Map.of("uploads", grants);
  }
}
