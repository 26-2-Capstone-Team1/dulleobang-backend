package com.dulleobang.common.config;

import com.dulleobang.common.contract.ApiQueryInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.*;

@Configuration
public class ApiWebConfiguration implements WebMvcConfigurer {
  private final ApiQueryInterceptor queries;

  public ApiWebConfiguration(ApiQueryInterceptor queries) {
    this.queries = queries;
  }

  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(queries);
  }
}
