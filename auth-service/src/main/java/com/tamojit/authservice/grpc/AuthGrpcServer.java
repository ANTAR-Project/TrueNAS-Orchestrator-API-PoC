package com.tamojit.authservice.grpc;

import com.tamojit.authservice.exception.InvalidTokenException;
import com.tamojit.authservice.service.AuthService;
import com.tamojit.grpc.auth.AuthGrpcServiceGrpc;
import com.tamojit.grpc.auth.ValidateTokenRequest;
import com.tamojit.grpc.auth.ValidateTokenResponse;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class AuthGrpcServer extends AuthGrpcServiceGrpc.AuthGrpcServiceImplBase {
    private final AuthService authService;
    private static final Logger logger = LoggerFactory.getLogger(AuthGrpcServer.class);

    public AuthGrpcServer(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public void validateToken(ValidateTokenRequest request, StreamObserver<ValidateTokenResponse> responseObserver) {
        try {
            logger.info("Validating token for request: {}", request);

            String username = authService.validate(request.getToken());
            responseObserver.onNext(ValidateTokenResponse.newBuilder()
                .setUsername(username)
                .build()
            );
            responseObserver.onCompleted();
        } catch (InvalidTokenException e) {
            responseObserver.onError(Status.UNAUTHENTICATED.withDescription(e.getMessage()).asRuntimeException());
        }
    }
}
