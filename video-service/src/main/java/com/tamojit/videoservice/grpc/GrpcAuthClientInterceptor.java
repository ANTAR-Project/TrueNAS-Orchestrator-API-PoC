package com.tamojit.videoservice.grpc;

import com.tamojit.videoservice.security.ServiceAuthTokenProvider;
import io.grpc.*;
import org.springframework.grpc.client.GlobalClientInterceptor;
import org.springframework.stereotype.Component;

@Component
@GlobalClientInterceptor
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
