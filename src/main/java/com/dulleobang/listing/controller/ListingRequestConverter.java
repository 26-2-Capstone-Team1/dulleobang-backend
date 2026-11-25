package com.dulleobang.listing.controller;

import com.dulleobang.common.contract.JsonInput;
import com.dulleobang.common.idempotency.service.IdempotencyService;
import com.dulleobang.common.json.StrictJson;
import com.dulleobang.listing.dto.ListingCreateRequest;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.AbstractHttpMessageConverter;
import org.springframework.http.converter.HttpMessageConverters;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class ListingRequestConverter implements WebMvcConfigurer {
  private static final int BODY_LIMIT = 10 * 1024 * 1024;
  private final JsonInput input;

  ListingRequestConverter(JsonInput input) {
    this.input = input;
  }

  @Override
  public void configureMessageConverters(HttpMessageConverters.ServerBuilder converters) {
    converters.addCustomConverter(
        new AbstractHttpMessageConverter<ListingCreateRequest>(MediaType.ALL) {
          @Override
          protected boolean supports(Class<?> type) {
            return type == ListingCreateRequest.class;
          }

          @Override
          public boolean canWrite(Class<?> type, MediaType mediaType) {
            return false;
          }

          @Override
          protected ListingCreateRequest readInternal(
              Class<? extends ListingCreateRequest> type, HttpInputMessage message)
              throws IOException {
            var headers = message.getHeaders();
            IdempotencyService.requireKey(headers.getFirst("Idempotency-Key"));
            var body =
                input.readMobile(
                    message.getBody().readNBytes(BODY_LIMIT + 1),
                    headers.getFirst(HttpHeaders.CONTENT_TYPE),
                    "MobilecreateListing");
            return StrictJson.bind(body, ListingCreateRequest.class);
          }

          @Override
          protected void writeInternal(ListingCreateRequest value, HttpOutputMessage message) {
            throw new UnsupportedOperationException();
          }
        });
  }
}
