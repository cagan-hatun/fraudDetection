package com.fraud.project.config;

import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;

/**
 * ml-service çağrıları (MlServiceClient) için circuit breaker. Bilinçli olarak
 * resilience4j-spring-boot3'ün @CircuitBreaker/AOP entegrasyonu yerine çekirdek
 * resilience4j-circuitbreaker kullanılıp MlServiceClient içinde programatik
 * sarmalanıyor: Spring Boot 4.1.1 çok yeni bir sürüm ve starter'ın autoconfigure/
 * actuator entegrasyonunun bununla uyumu doğrulanmamış (bkz. MlServiceConfig'teki
 * diğer Boot 4.1.1 uyumsuzlukları) — çekirdek kütüphanenin Spring'e bağımlılığı
 * olmadığı için bu riski taşımıyor.
 *
 * Kasıtlı olarak retry EKLENMİYOR: mesaj seviyesinde retry zaten Kafka tarafında
 * (KafkaErrorHandlingConfig, 3 deneme + 1sn backoff) var; burada ikinci bir retry
 * katmanı eklemek aynı işi üst üste yapar. Devre açıkken (veya son deneme de
 * başarısız olursa) fallback YOK — hata olduğu gibi yukarı fırlatılır ve Kafka'nın
 * retry/DLQ mekanizması devreye girer.
 */
@Configuration
public class ResilienceConfig {

    @Bean
    public CircuitBreaker mlServiceCircuitBreaker() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
            .failureRateThreshold(50)
            .slidingWindowSize(10)
            .minimumNumberOfCalls(5)
            .waitDurationInOpenState(Duration.ofSeconds(15))
            .permittedNumberOfCallsInHalfOpenState(3)
            .build();
        return CircuitBreaker.of("ml-service", config);
    }
}
