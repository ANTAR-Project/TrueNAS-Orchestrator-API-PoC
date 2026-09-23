package com.tamojit.videoservice.grpc;

import com.tamojit.videoservice.security.ServiceAuthTokenProvider;
import io.grpc.*;

// Not @GlobalClientInterceptor — only applied to the nas-orchestrator stub in GrpcClientConfig.
// Applying this globally would intercept the authGrpcStub calls too, triggering tokenProvider.getToken()
// (a blocking REST call) from inside the TokenValidationFilter's own gRPC auth call.
public class GrpcAuthClientInterceptor implements ClientInterceptor {
    private static final Metadata.Key<String> AUTH_TOKEN_METADATA_KEY = Metadata.Key.of("x-auth-token", Metadata.ASCII_STRING_MARSHALLER);

    private final ServiceAuthTokenProvider tokenProvider;

    public GrpcAuthClientInterceptor(ServiceAuthTokenProvider tokenProvider) {
        this.tokenProvider = tokenProvider;
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
        MethodDescriptor<ReqT, RespT> method,
        CallOptions callOptions,
        Channel next
    ) {
        return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, callOptions)) {
            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                headers.put(AUTH_TOKEN_METADATA_KEY, tokenProvider.getToken());
                super.start(responseListener, headers);
            }
        };
    }
}
