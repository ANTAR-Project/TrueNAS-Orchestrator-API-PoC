package com.tamojit.videoservice.config;

import com.tamojit.grpc.auth.AuthGrpcServiceGrpc;
import com.tamojit.grpc.filetransfer.FileTransferGrpcServiceGrpc;
import com.tamojit.videoservice.grpc.GrpcAuthClientInterceptor;
import com.tamojit.videoservice.security.ServiceAuthTokenProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.GrpcChannelFactory;

@Configuration
public class GrpcClientConfig {

    // No service token on auth calls — auth-service has no gRPC server interceptor
    @Bean
    public AuthGrpcServiceGrpc.AuthGrpcServiceBlockingStub authGrpcStub(GrpcChannelFactory channels) {
        return AuthGrpcServiceGrpc.newBlockingStub(channels.createChannel("auth-service"));
    }

    // Service token required — nas-orchestrator's GrpcAuthInterceptor validates it
    @Bean
    public FileTransferGrpcServiceGrpc.FileTransferGrpcServiceStub fileTransferGrpcAsyncStub(
        GrpcChannelFactory channels,
        ServiceAuthTokenProvider tokenProvider
    ) {
        GrpcAuthClientInterceptor interceptor = new GrpcAuthClientInterceptor(tokenProvider);
        return FileTransferGrpcServiceGrpc.newStub(channels.createChannel("nas-orchestrator"))
            .withInterceptors(interceptor);
    }

    @Bean
    public FileTransferGrpcServiceGrpc.FileTransferGrpcServiceBlockingStub fileTransferGrpcBlockingStub(
        GrpcChannelFactory channels,
        ServiceAuthTokenProvider tokenProvider
    ) {
        GrpcAuthClientInterceptor interceptor = new GrpcAuthClientInterceptor(tokenProvider);
        return FileTransferGrpcServiceGrpc.newBlockingStub(channels.createChannel("nas-orchestrator"))
            .withInterceptors(interceptor);
    }
}
