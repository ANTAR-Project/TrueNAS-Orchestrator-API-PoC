package com.tamojit.authservice.controller;

import com.tamojit.authservice.dto.TokenRequest;
import com.tamojit.authservice.dto.TokenResponse;
import com.tamojit.authservice.dto.ValidateResponse;
import com.tamojit.authservice.service.AuthService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth-service")
@Validated
public class AuthController {
    private static final String TOKEN_FORMAT_REGEX = "^[a-f0-9]{32}$";
    private static final String TOKEN_FORMAT_MSG = "Token is malformed";

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/tokens")
    public ResponseEntity<TokenResponse> issueToken(@Valid @RequestBody TokenRequest request) {
        String token = authService.issueToken(request.username());
        return ResponseEntity.ok(new TokenResponse(request.username(), token));
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
