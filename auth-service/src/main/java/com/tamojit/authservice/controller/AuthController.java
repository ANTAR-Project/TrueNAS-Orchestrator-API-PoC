package com.tamojit.authservice.controller;

import com.tamojit.authservice.dto.LoginRequest;
import com.tamojit.authservice.dto.RegisterRequest;
import com.tamojit.authservice.dto.TokenResponse;
import com.tamojit.authservice.dto.ValidateResponse;
import com.tamojit.authservice.service.AuthService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth-service")
@Validated
public class AuthController {
    private static final String TOKEN_FORMAT_MSG = "Token is malformed";
    private static final String TOKEN_FORMAT_REGEX = "^[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$";

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<Void> register(@Valid @RequestBody RegisterRequest request) {
        authService.register(
            request.username(),
            request.password(),
            request.accountType()
        );

        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        String token = authService.login(request.username(), request.password());
        return ResponseEntity.ok(
            new TokenResponse(request.username(), token));
    }

    @GetMapping("/tokens/validate")
    public ResponseEntity<ValidateResponse> validateToken(
        @RequestParam("token")
        @NotBlank(message = "token is required")
        @Pattern(regexp = TOKEN_FORMAT_REGEX, message = TOKEN_FORMAT_MSG)
        String token
    ) {
        String username = authService.validate(token);
        return ResponseEntity.ok(new ValidateResponse(username));
    }
}
