package com.dulleobang.upload.service;

import com.dulleobang.common.contract.ContractValidator;
import com.dulleobang.common.json.StrictJson;
import com.dulleobang.upload.domain.UploadSession;
import com.dulleobang.upload.dto.UploadBatch;
import com.dulleobang.upload.mapper.UploadMapper;
import com.dulleobang.upload.repository.UploadRepository;
import com.dulleobang.upload.validation.UploadFileClassifier;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UploadBatchService {
  private final UploadRepository repository;
  private final UploadAccessService access;
  private final UploadFileClassifier classifier;
  private final ContractValidator contracts;
  private final Clock clock;

  public UploadBatchService(
      UploadRepository repository,
      UploadAccessService access,
      UploadFileClassifier classifier,
      ContractValidator contracts,
      Clock clock) {
    this.repository = repository;
    this.access = access;
    this.classifier = classifier;
    this.contracts = contracts;
    this.clock = clock;
  }

  public List<JsonNode> check(UUID actor, JsonNode input) {
    contracts.validate("UploadSessionCreate", StrictJson.stringify(input));
    authorize(actor, input);
    return classifier.classify(input);
  }

  public void authorize(UUID actor, JsonNode input) {
    access.owned(
        actor, input.path("targetType").asText(), UUID.fromString(input.path("targetId").asText()));
  }

  @Transactional
  public UploadBatch open(UUID actor, JsonNode input) {
    var files = check(actor, input);
    var now = clock.instant();
    var session =
        new UploadSession(
            UUID.randomUUID(),
            actor,
            input.path("purpose").asText(),
            input.path("targetType").asText(),
            UUID.fromString(input.path("targetId").asText()),
            "OPEN",
            now,
            now.plusSeconds(86400));
    repository.create(session);
    for (int n = 0; n < files.size(); n++)
      repository.add(UploadMapper.file(session, files.get(n), classifier.format(files.get(n)), n));
    return new UploadBatch(session, repository.files(session.id()));
  }
}
