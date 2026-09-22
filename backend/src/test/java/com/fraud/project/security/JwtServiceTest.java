package com.fraud.project.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Date;

import org.junit.jupiter.api.Test;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;

class JwtServiceTest {

    // Gerçek uygulamadakiyle aynı uzunlukta (HS256 için en az 256 bit) ama
    // teste özel bir secret — application.properties'teki gerçek secret'a
    // testlerin bağımlı olmasını istemiyoruz.
    private static final String TEST_SECRET = "test-secret-key-must-be-at-least-256-bits-long-for-hs256!!";

    @Test
    void generateToken_thenParseClaims_roundTripsUsernameAndRole() {
        JwtService jwtService = new JwtService(TEST_SECRET, 60_000);

        String token = jwtService.generateToken("analyst", "ANALYST");
        Claims claims = jwtService.parseClaims(token);

        assertThat(claims.getSubject()).isEqualTo("analyst");
        assertThat(claims.get("role", String.class)).isEqualTo("ANALYST");
        assertThat(claims.getExpiration()).isAfter(new Date());
    }

    @Test
    void parseClaims_expiredToken_throwsJwtException() {
        // Süresi 0ms (anında dolan) bir token üretip, birkaç ms bekleyip
        // parse etmeye çalışıyoruz — ExpiredJwtException, JwtException'ın
        // alt sınıfı.
        JwtService jwtService = new JwtService(TEST_SECRET, 0);
        String token = jwtService.generateToken("analyst", "ANALYST");

        assertThatThrownBy(() -> {
            Thread.sleep(50);
            jwtService.parseClaims(token);
        }).isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    void parseClaims_tamperedToken_throwsJwtException() {
        JwtService jwtService = new JwtService(TEST_SECRET, 60_000);
        String token = jwtService.generateToken("analyst", "ANALYST");
        String tampered = token.substring(0, token.length() - 1) + (token.endsWith("a") ? "b" : "a");

        assertThatThrownBy(() -> jwtService.parseClaims(tampered))
            .isInstanceOf(JwtException.class);
    }

    @Test
    void parseClaims_differentSecret_throwsJwtException() {
        JwtService issuer = new JwtService(TEST_SECRET, 60_000);
        JwtService verifierWithDifferentSecret =
            new JwtService("a-completely-different-secret-key-thats-also-256-bits-long!!", 60_000);

        String token = issuer.generateToken("analyst", "ANALYST");

        assertThatThrownBy(() -> verifierWithDifferentSecret.parseClaims(token))
            .isInstanceOf(JwtException.class);
    }
}
