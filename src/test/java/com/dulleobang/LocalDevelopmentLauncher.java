package com.dulleobang;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.boot.SpringApplication;

/** Docker-free development only. Uses a fresh temporary real PostgreSQL instance. */
public final class LocalDevelopmentLauncher {
  public static void main(String[] args) throws Exception {
    var postgres = EmbeddedPostgres.builder().setPort(0).start();
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  try {
                    postgres.close();
                  } catch (Exception ignored) {
                  }
                }));
    System.setProperty("spring.datasource.url", postgres.getJdbcUrl("postgres", "postgres"));
    System.setProperty("spring.datasource.username", "postgres");
    System.setProperty("spring.datasource.password", "");
    SpringApplication.run(DulleobangApplication.class, args);
  }
}
