package com.fraud.project.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;

class ResilienceConfigTest {

    private final CircuitBreaker circuitBreaker = new ResilienceConfig().mlServiceCircuitBreaker();

    @Test
    void startsClosedAndAllowsCallsWhileHealthy() {
        String result = circuitBreaker.executeSupplier(() -> "ok");

        assertThat(result).isEqualTo("ok");
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void opensAfterMinimumNumberOfCallsAllFailAndRejectsFurtherCallsImmediately() {
        // minimumNumberOfCalls=5, failureRateThreshold=%50 -> 5 ardışık hata
        // (%100 hata oranı) devreyi açmaya yeter.
        for (int i = 0; i < 5; i++) {
            circuitBreaker.onError(0, TimeUnit.NANOSECONDS, new RuntimeException("ml-service unreachable"));
        }

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThatThrownBy(() -> circuitBreaker.executeSupplier(() -> "ml-service'e hiç gidilmemeli"))
            .isInstanceOf(CallNotPermittedException.class);
    }
}
