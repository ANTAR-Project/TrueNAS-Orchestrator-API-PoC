package com.tamojit.nasorchestrator.config;

import com.tamojit.grpc.auth.AuthGrpcServiceGrpc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.GrpcChannelFactory;

@Configuration
public class GrpcClientConfig {
    @Bean
    public AuthGrpcServiceGrpc.AuthGrpcServiceBlockingStub authGrpcServiceBlockingStub(GrpcChannelFactory channels) {
        return AuthGrpcServiceGrpc.newBlockingStub(channels.createChannel("auth-service"));
    }
}
