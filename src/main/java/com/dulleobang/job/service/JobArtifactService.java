package com.dulleobang.job.service;

import com.dulleobang.job.domain.JobRecord;
import com.dulleobang.job.mapper.ArtifactMapper;
import com.dulleobang.job.repository.JobArtifactRepository;
import com.dulleobang.job.validation.ArtifactPolicy;
import com.dulleobang.upload.domain.AssetFile;
import com.dulleobang.upload.repository.UploadRepository;
import com.dulleobang.upload.service.SignedUrlService;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class JobArtifactService {
  private final JobArtifactRepository repository;
  private final UploadRepository uploads;
  private final SignedUrlService signatures;
  private final ArtifactPolicy policy;
  private final Clock clock;

  public JobArtifactService(
      JobArtifactRepository repository,
      UploadRepository uploads,
      SignedUrlService signatures,
      ArtifactPolicy policy,
      Clock clock) {
    this.repository = repository;
    this.uploads = uploads;
    this.signatures = signatures;
    this.policy = policy;
    this.clock = clock;
  }

  public Map<String, Object> create(JobRecord job, JsonNode input) {
    policy.validate(job.type(), input.path("files"));
    var current = repository.files(job.id(), job.attempt());
    var grants = new ArrayList<Map<String, Object>>();
    int ordinal = 0;
    for (var info : input.path("files")) {
      var match =
          current.stream()
              .filter(
                  f ->
                      f.clientId().toString().equals(info.path("clientFileId").asText())
                          || (!f.role().equals("SOURCE_MESH")
                              && f.role().equals(info.path("role").asText())))
              .findFirst();
      AssetFile file;
      if (match.isPresent()) {
        file = match.get();
        policy.match(file, info);
      } else {
        file = ArtifactMapper.file(job, info, ordinal, clock.instant());
        uploads.add(file);
        repository.link(file.id(), job.id(), job.attempt());
      }
      ordinal++;
      grants.add(signatures.grant(file, "PUT"));
    }
    return Map.of("uploads", grants);
  }
}
