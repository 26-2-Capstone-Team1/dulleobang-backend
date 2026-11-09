package com.dulleobang;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.dulleobang.common.contract.ApiInventory;
import com.dulleobang.common.exception.ProblemWriter;
import com.dulleobang.common.security.*;
import com.dulleobang.common.security.repository.SessionRepository;
import com.nimbusds.jwt.JWTClaimsSet;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.*;

class FailureHandlingTest {
  @Test
  void handlesSessionFailure() throws Exception {
    var verifier = mock(TokenVerifier.class);
    var claims =
        new JWTClaimsSet.Builder()
            .subject("11111111-1111-4111-8111-111111111111")
            .claim("sid", "22222222-2222-4222-8222-222222222222")
            .build();
    when(verifier.verify("token", "user")).thenReturn(claims);
    var sessions = mock(SessionRepository.class);
    when(sessions.active(anyString(), anyString(), any()))
        .thenThrow(new DataAccessResourceFailureException("private DB details"));
    var filter =
        new BearerAuthenticationFilter(
            new ApiInventory(), verifier, sessions, Clock.systemUTC(), new ProblemWriter());
    var request = new MockHttpServletRequest("GET", "/api/listings");
    request.addHeader("Authorization", "Bearer token");
    var response = new MockHttpServletResponse();
    filter.doFilter(request, response, new MockFilterChain());
    assertThat(response.getStatus()).isEqualTo(503);
    assertThat(response.getHeader("Retry-After")).isEqualTo("1");
    assertThat(response.getContentAsString())
        .contains("DATABASE_UNAVAILABLE")
        .doesNotContain("private DB details");
  }
}
