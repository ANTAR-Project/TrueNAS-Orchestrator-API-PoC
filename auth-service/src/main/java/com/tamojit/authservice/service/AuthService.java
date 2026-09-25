package com.tamojit.authservice.service;

import com.tamojit.authservice.exception.InvalidCredentialsException;
import com.tamojit.authservice.exception.UsernameAlreadyExistsException;
import com.tamojit.authservice.model.User;
import com.tamojit.authservice.repository.UserRepository;
import com.tamojit.authservice.security.JwtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(
        UserRepository userRepository,
        PasswordEncoder passwordEncoder,
        JwtService jwtService
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional
    public void register(String username, String rawPassword, String accountType) {
        if (userRepository.existsByUsername(username)) {
            throw new UsernameAlreadyExistsException("Username '" + username + "' is already registered");
        }

        User user = new User(
            username,
            passwordEncoder.encode(rawPassword),
            accountType
        );
        userRepository.save(user);

        log.info("Registered new {} account: {}", accountType, username);
    }

    public String login(String username, String rawPassword) {
        User user = userRepository.findByUsername(username)
            .orElseThrow(() -> new InvalidCredentialsException("Invalid username or password"));

        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            throw new InvalidCredentialsException("Invalid username or password");
        }

        log.info("Issued token for username: {}", username);
        return jwtService.issueToken(user.getUsername(), user.getAccountType());
    }

    public String validate(String token) {
        return jwtService.verifyAndGetUsername(token);
    }
}
