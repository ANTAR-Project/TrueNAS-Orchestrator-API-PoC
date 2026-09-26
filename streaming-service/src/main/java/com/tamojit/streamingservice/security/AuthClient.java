package com.tamojit.streamingservice.security;

import com.tamojit.grpc.auth.AuthGrpcServiceGrpc;
import com.tamojit.grpc.auth.ValidateTokenRequest;
import com.tamojit.grpc.auth.ValidateTokenResponse;
import com.tamojit.streamingservice.dto.ValidationOutcome;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class AuthClient {
    private static final Logger logger = LoggerFactory.getLogger(AuthClient.class);

    private final AuthGrpcServiceGrpc.AuthGrpcServiceBlockingStub authGrpcStub;

    public AuthClient(AuthGrpcServiceGrpc.AuthGrpcServiceBlockingStub authGrpcStub) {
        this.authGrpcStub = authGrpcStub;
    }

    public ValidationOutcome validate(String token) {
        try {
            ValidateTokenResponse response = authGrpcStub.validateToken(
                ValidateTokenRequest.newBuilder()
                    .setToken(token)
                    .build()
            );
            logger.info("Validate token response: {}", response);

            return new ValidationOutcome(true, true, response.getUsername());
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.UNAUTHENTICATED) {
                return new ValidationOutcome(false, true, null);
            }

            return new ValidationOutcome(false, false, null);
        }
    }
}
