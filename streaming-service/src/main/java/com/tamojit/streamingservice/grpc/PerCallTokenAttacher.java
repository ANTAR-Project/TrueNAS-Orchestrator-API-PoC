package com.tamojit.streamingservice.grpc;

import io.grpc.Metadata;
import io.grpc.stub.AbstractStub;
import io.grpc.stub.MetadataUtils;
import org.springframework.stereotype.Component;

@Component
public class PerCallTokenAttacher {
    private static final Metadata.Key<String> AUTH_TOKEN_METADATA_KEY = Metadata.Key.of("x-auth-token", Metadata.ASCII_STRING_MARSHALLER);

    // Attaches a caller-supplied token to a single gRPC call, scoped to just that call.
    // <S extends AbstractStub<S>> generic bound - AbstractStub itself is declared with in grpc-java, so this method works for any stub type
    public <S extends AbstractStub<S>> S attach(S stub, String token) {
        Metadata metadata = new Metadata();
        metadata.put(AUTH_TOKEN_METADATA_KEY, token != null ? token : "");

        return stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata));
    }
}
