package com.dulleobang.infrastructure.oauth;

import com.dulleobang.auth.domain.SocialIdentity;
import com.dulleobang.auth.service.SocialTokenVerifier;
import com.dulleobang.common.exception.ApiException;
import com.dulleobang.common.json.StrictJson;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class KakaoTokenVerifier implements SocialTokenVerifier {
  private final ProviderHttp http;
  private final String appId;

  public KakaoTokenVerifier(ProviderHttp http, @Value("${KAKAO_APP_ID:}") String appId) {
    this.http = http;
    this.appId = appId;
  }

  public String provider() {
    return "KAKAO";
  }

  public SocialIdentity verify(String token) {
    if (appId.isBlank()) throw new ApiException(503, "OAUTH_NOT_CONFIGURED");
    var headers = Map.of("Authorization", "Bearer " + token);
    var info = get("https://kapi.kakao.com/v1/user/access_token_info", headers);
    if (!info.path("id").isIntegralNumber()
        || info.path("id").asLong() <= 0
        || !info.path("app_id").asText().equals(appId)
        || !info.path("expires_in").isNumber()
        || info.path("expires_in").asLong() <= 0) throw invalid();
    var user = get("https://kapi.kakao.com/v2/user/me", headers);
    if (!user.path("id").isIntegralNumber() || !user.path("id").equals(info.path("id")))
      throw invalid();
    var account = user.path("kakao_account");
    String email =
        account.path("is_email_valid").asBoolean() && account.path("is_email_verified").asBoolean()
            ? account.path("email").asText(null)
            : null;
    return new SocialIdentity(
        provider(),
        info.path("id").asText(),
        email,
        account.path("profile").path("nickname").asText("카카오 사용자"));
  }

  private JsonNode get(String url, Map<String, String> headers) {
    var reply = http.get(URI.create(url), headers);
    if (reply.status() == 400 || reply.status() == 401) throw invalid();
    if (reply.status() != 200) throw new ApiException(503, "OAUTH_PROVIDER_UNAVAILABLE");
    try {
      return StrictJson.parse(reply.body());
    } catch (ApiException ex) {
      throw new ApiException(503, "OAUTH_PROVIDER_UNAVAILABLE");
    }
  }

  private ApiException invalid() {
    return new ApiException(401, "INVALID_OAUTH_TOKEN");
  }
}
