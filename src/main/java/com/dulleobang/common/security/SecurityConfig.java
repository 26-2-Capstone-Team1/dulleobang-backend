package com.dulleobang.common.security;

import com.dulleobang.common.config.BackendProperties;
import com.dulleobang.common.contract.ApiInventory;
import com.dulleobang.common.exception.ProblemWriter;
import com.dulleobang.common.ratelimit.RequestRateFilter;
import com.dulleobang.common.ratelimit.service.RateLimitService;
import com.dulleobang.common.security.repository.SessionRepository;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
public class SecurityConfig {
  @Bean
  public PasswordEncoder passwordEncoder() {
    return new Argon2PasswordEncoder(16, 32, 4, 65536, 3);
  }

  @Bean
  @org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(
      type =
          org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
  public SecurityFilterChain security(
      HttpSecurity http,
      ApiInventory inventory,
      TokenVerifier tokens,
      SessionRepository sessions,
      Clock clock,
      ProblemWriter problems,
      BackendProperties properties,
      RateLimitService limits,
      @org.springframework.beans.factory.annotation.Value("${dulleobang.rate.enabled:true}")
          boolean rateEnabled)
      throws Exception {
    var cors = new CorsConfiguration();
    cors.setAllowedOrigins(
        properties.corsOrigins() == null
            ? List.of()
            : Arrays.stream(properties.corsOrigins().split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList());
    if (cors.getAllowedOrigins().contains("*"))
      throw new IllegalArgumentException("Use explicit CORS origins");
    cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
    cors.setAllowedHeaders(
        List.of(
            "Authorization",
            "Content-Type",
            "Idempotency-Key",
            "If-Match",
            "If-None-Match",
            "X-User-Id",
            "X-Job-Lease-Token"));
    cors.setExposedHeaders(List.of("ETag", "X-Request-Id", "Location", "Retry-After"));
    var source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", cors);
    http.csrf(c -> c.disable())
        .cors(c -> c.configurationSource(source))
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(a -> a.anyRequest().permitAll())
        .httpBasic(c -> c.disable())
        .formLogin(c -> c.disable())
        .logout(c -> c.disable());
    http.addFilterBefore(
        new RequestContextFilter(), org.springframework.web.filter.CorsFilter.class);
    http.addFilterBefore(
        new BearerAuthenticationFilter(inventory, tokens, sessions, clock, problems),
        UsernamePasswordAuthenticationFilter.class);
    http.addFilterAfter(
        new RequestRateFilter(inventory, limits, problems, rateEnabled),
        BearerAuthenticationFilter.class);
    return http.build();
  }
}
