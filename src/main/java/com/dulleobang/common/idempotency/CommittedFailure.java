package com.dulleobang.common.idempotency;

import com.dulleobang.common.exception.ApiException;

/** A failed operation whose defensive database changes must commit, without caching a response. */
public final class CommittedFailure extends RuntimeException {
  private final ApiException failure;

  public CommittedFailure(ApiException failure) {
    super(failure);
    this.failure = failure;
  }

  public ApiException failure() {
    return failure;
  }
}
