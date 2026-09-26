package com.tamojit.authservice.bootstrap;

import com.tamojit.authservice.exception.UsernameAlreadyExistsException;
import com.tamojit.authservice.service.AuthService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "auth.bootstrap.enabled", havingValue = "true", matchIfMissing = true)
public class ServiceAccountBootstrap {
    private static final Logger log = LoggerFactory.getLogger(ServiceAccountBootstrap.class);
    private final AuthService authService;

    @Value("${service.accounts.video-service.password}")
    private String videoServicePassword;

    @Value("${service.accounts.encoding-service.password}")
    private String encodingServicePassword;

    public ServiceAccountBootstrap(AuthService authService) {
        this.authService = authService;
    }

    @PostConstruct
    public void seedServiceAccounts() {
        registerIfAbsent("video-service", videoServicePassword);
        registerIfAbsent("encoding-service", encodingServicePassword);
    }

    private void registerIfAbsent(String serviceName, String password) {
        try {
            authService.register(serviceName, password, "SERVICE");
            log.info("Bootstrapped service account: {}", serviceName);
        } catch (UsernameAlreadyExistsException e) {
            log.debug("Service account already exists: {}", serviceName);
        }
    }
}
