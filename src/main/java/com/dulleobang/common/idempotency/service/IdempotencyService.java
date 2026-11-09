package com.dulleobang.common.idempotency.service;

import com.dulleobang.common.exception.ApiException;
import com.dulleobang.common.idempotency.*;
import com.dulleobang.common.idempotency.repository.IdempotencyRepository;
import com.dulleobang.common.json.StrictJson;
import java.time.Clock;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class IdempotencyService {
  private final IdempotencyRepository repository;
  private final ResponseCipher cipher;
  private final TransactionTemplate transactions;
  private final Clock clock;

  public IdempotencyService(
      IdempotencyRepository repository,
      ResponseCipher cipher,
      org.springframework.transaction.PlatformTransactionManager manager,
      Clock clock) {
    this.repository = repository;
    this.cipher = cipher;
    this.transactions = new TransactionTemplate(manager);
    this.clock = clock;
  }

  public static UUID requireKey(String requestKey) {
    if (requestKey == null || requestKey.isEmpty())
      throw new ApiException(400, "IDEMPOTENCY_KEY_REQUIRED");
    try {
      UUID key = UUID.fromString(requestKey);
      if (!key.toString().equalsIgnoreCase(requestKey)) throw new IllegalArgumentException();
      return key;
    } catch (Exception ex) {
      throw new ApiException(400, "INVALID_PARAMETER");
    }
  }

  public StoredResponse execute(
      String actor,
      String method,
      String path,
      String requestKey,
      String json,
      Supplier<StoredResponse> action) {
    return execute(actor, method, path, requestKey, json, () -> {}, action);
  }

  public StoredResponse execute(
      String actor,
      String method,
      String path,
      String requestKey,
      String json,
      Runnable authorize,
      Supplier<StoredResponse> action) {
    UUID key = requireKey(requestKey);
    String hash = StrictJson.digest(json);
    String binding =
        StrictJson.canonical(StrictJson.stringify(List.of(actor, method, path, key.toString())));
    Outcome outcome =
        transactions.execute(
            status -> {
              var entry = repository.lock(actor, method, path, key, hash, clock.instant());
              authorize.run();
              if (!entry.hash().equals(hash)) throw new ApiException(409, "IDEMPOTENCY_KEY_REUSED");
              if (entry.payload() != null)
                return new Outcome(decode(cipher.decrypt(entry.payload(), binding)), null);
              try {
                var response =
                    Objects.requireNonNull(
                        action.get(), "Idempotent action must return a response");
                repository.save(
                    actor,
                    method,
                    path,
                    key,
                    cipher.encrypt(StrictJson.stringify(response), binding));
                return new Outcome(response, null);
              } catch (CommittedFailure failure) {
                return new Outcome(null, failure.failure());
              }
            });
    if (outcome.failure() != null) throw outcome.failure();
    return outcome.response();
  }

  public Optional<StoredResponse> lookup(
      String actor, String method, String path, String requestKey, String json) {
    UUID key = requireKey(requestKey);
    var entry = repository.find(actor, method, path, key, clock.instant());
    if (entry.isEmpty()) return Optional.empty();
    if (!entry.get().hash().equals(StrictJson.digest(json)))
      throw new ApiException(409, "IDEMPOTENCY_KEY_REUSED");
    if (entry.get().payload() == null) return Optional.empty();
    String binding =
        StrictJson.canonical(StrictJson.stringify(List.of(actor, method, path, key.toString())));
    return Optional.of(decode(cipher.decrypt(entry.get().payload(), binding)));
  }

  private StoredResponse decode(String json) {
    var node = StrictJson.parse(json);
    var headers = new LinkedHashMap<String, String>();
    node.path("headers")
        .fields()
        .forEachRemaining(e -> headers.put(e.getKey(), e.getValue().asText()));
    return new StoredResponse(node.path("status").asInt(), node.path("body").asText(), headers);
  }

  private record Outcome(StoredResponse response, ApiException failure) {}
}
