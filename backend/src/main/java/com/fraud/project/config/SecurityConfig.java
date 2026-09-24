package com.fraud.project.config;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.fraud.project.security.JwtAuthenticationFilter;

/**
 * JWT ile stateless kimlik doğrulama: `/api/auth/login` herkese açık, geri
 * kalan her şey geçerli bir Bearer token gerektiriyor. `DaoAuthenticationProvider`
 * elle tanımlanmadı — Spring Security, context'teki TEK `UserDetailsService`
 * (`AppUserDetailsService`) ve `PasswordEncoder` bean'ini otomatik bulup
 * kullanıyor.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtAuthenticationFilter jwtAuthenticationFilter)
        throws Exception {
        return http
            .csrf(csrf -> csrf.disable())
            .cors(cors -> cors.configurationSource(corsConfigurationSource))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/**").permitAll()
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                // DLQ redrive gibi altyapı kurtarma işlemleri bir analistin değil, bir
                // operasyon/altyapı sorumlusunun yapacağı bir iş — bu yüzden projede
                // ilk kez rol bazlı bir ayrım burada devreye giriyor.
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated())
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            .build();
    }

    /**
     * Frontend (Vite dev server, farklı port) tarayıcıdan istek attığı için CORS
     * izni gerekiyor — Spring Security varsayılan olarak bunu tamamen kapalı
     * tutuyor. İzin verilen origin'ler application.properties'ten geliyor,
     * kod içine sabitlenmedi (prod'da farklı bir domain olacaktır).
     */
    private final CorsConfigurationSource corsConfigurationSource;

    public SecurityConfig(@Value("${cors.allowed-origins}") List<String> allowedOrigins) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        this.corsConfigurationSource = source;
    }
}
