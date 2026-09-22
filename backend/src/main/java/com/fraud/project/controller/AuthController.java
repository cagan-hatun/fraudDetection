package com.fraud.project.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fraud.project.entity.AppUser;
import com.fraud.project.repository.AppUserRepository;
import com.fraud.project.security.JwtService;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final AppUserRepository appUserRepository;

    public AuthController(
        AuthenticationManager authenticationManager,
        JwtService jwtService,
        AppUserRepository appUserRepository
    ) {
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.appUserRepository = appUserRepository;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@RequestBody LoginRequest request) {
        try {
            authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.username(), request.password())
            );
        } catch (AuthenticationException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // authenticate() exception atmadıysa kullanıcı adı/şifre doğrudur —
        // token'a koyacağımız rolü almak için AppUser'ı tekrar okuyoruz.
        AppUser appUser = appUserRepository.findByUsername(request.username()).orElseThrow();
        String token = jwtService.generateToken(appUser.getUsername(), appUser.getRole().name());
        return ResponseEntity.ok(new LoginResponse(token));
    }
}
