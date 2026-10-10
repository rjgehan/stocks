package com.stocks;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * The stocks backend: one Spring Boot application, run as one instance (D1). HTTP and WebSocket on 8080, actuator on
 * 8081. Every workstream's {@code @ConfigurationProperties} record under {@code com.stocks} is picked up by the scan.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class StocksApplication {

  public static void main(String[] args) {
    // §6.4: the JVM runs in UTC. Deployments also pass -Duser.timezone=UTC; this covers IDE and plain `java -jar` runs.
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    SpringApplication.run(StocksApplication.class, args);
  }
}
