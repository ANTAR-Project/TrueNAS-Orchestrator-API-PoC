package com.tamojit.nasorchestrator.grpc;

import com.tamojit.nasorchestrator.dto.ValidationOutcome;
import com.tamojit.nasorchestrator.security.AuthClient;
import io.grpc.*;
import org.springframework.grpc.server.GlobalServerInterceptor;
import org.springframework.stereotype.Component;

@Component
@GlobalServerInterceptor
public class GrpcAuthInterceptor implements ServerInterceptor {
    public static final Context.Key<String> USERNAME_CONTEXT_KEY = Context.key("authUsername");
    private static final Metadata.Key<String> AUTH_TOKEN_METADATA_KEY =
        Metadata.Key.of("x-auth-token", Metadata.ASCII_STRING_MARSHALLER);

    private final AuthClient authClient;

    public GrpcAuthInterceptor(AuthClient authClient) {
        this.authClient = authClient;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
        ServerCall<ReqT, RespT> call,
        Metadata headers,
        ServerCallHandler<ReqT, RespT> next
    ) {
        String token = headers.get(AUTH_TOKEN_METADATA_KEY);

        if (token == null || token.isBlank()) {
            call.close(Status.UNAUTHENTICATED.withDescription("Missing x-auth-token metadata"), new Metadata());
            return new ServerCall.Listener<>() {
            };
        }

        ValidationOutcome outcome = authClient.validate(token);

        if (!outcome.reachable()) {
            call.close(Status.UNAVAILABLE.withDescription("auth-service is unreachable"), new Metadata());
            return new ServerCall.Listener<>() {
            };
        }

        if (!outcome.valid()) {
            call.close(Status.UNAUTHENTICATED.withDescription("Token is invalid, expired, or was revoked"), new Metadata());
            return new ServerCall.Listener<>() {
            };
        }

        Context context = Context.current().withValue(USERNAME_CONTEXT_KEY, outcome.username());
        return Contexts.interceptCall(context, call, headers, next);
    }
}
