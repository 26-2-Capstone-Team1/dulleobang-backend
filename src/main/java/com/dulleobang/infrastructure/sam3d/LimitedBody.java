package com.dulleobang.infrastructure.sam3d;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
  private final int maximum;
  private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
  private final CompletableFuture<byte[]> result = new CompletableFuture<>();
  private Flow.Subscription subscription;

  LimitedBody(int maximum) {
    this.maximum = maximum;
  }

  public CompletionStage<byte[]> getBody() {
    return result;
  }

  public void onSubscribe(Flow.Subscription subscription) {
    this.subscription = subscription;
    subscription.request(1);
  }

  public void onNext(List<ByteBuffer> buffers) {
    for (var buffer : buffers) {
      if (buffer.remaining() > maximum - bytes.size()) {
        subscription.cancel();
        result.completeExceptionally(new java.io.IOException("SAM3D response limit exceeded"));
        return;
      }
      byte[] chunk = new byte[buffer.remaining()];
      buffer.get(chunk);
      bytes.writeBytes(chunk);
    }
    subscription.request(1);
  }

  public void onError(Throwable error) {
    result.completeExceptionally(error);
  }

  public void onComplete() {
    result.complete(bytes.toByteArray());
  }
}
