package com.dulleobang;

import com.dulleobang.common.config.BackendProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(BackendProperties.class)
public class DulleobangApplication {
  public static void main(String[] args) {
    var application = new SpringApplication(DulleobangApplication.class);
    boolean admin =
        java.util.Arrays.stream(args).anyMatch(a -> a.startsWith("--dulleobang.admin.command="));
    if (admin) {
      application.setWebApplicationType(org.springframework.boot.WebApplicationType.NONE);
      application.setDefaultProperties(java.util.Map.of("dulleobang.scheduler.enabled", "false"));
    }
    var context = application.run(args);
    if (admin) context.close();
  }
}
