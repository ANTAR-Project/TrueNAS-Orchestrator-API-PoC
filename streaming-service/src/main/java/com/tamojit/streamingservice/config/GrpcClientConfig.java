package com.tamojit.streamingservice.config;

import com.tamojit.grpc.auth.AuthGrpcServiceGrpc;
import com.tamojit.grpc.streaming.StreamingGrpcServiceGrpc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.GrpcChannelFactory;

@Configuration
public class GrpcClientConfig {
    @Bean
    public AuthGrpcServiceGrpc.AuthGrpcServiceBlockingStub authGrpcStub(GrpcChannelFactory channels) {
        return AuthGrpcServiceGrpc.newBlockingStub(channels.createChannel("auth-service"));
    }

    @Bean
    public StreamingGrpcServiceGrpc.StreamingGrpcServiceBlockingStub streamingGrpcStub(GrpcChannelFactory channels) {
        return StreamingGrpcServiceGrpc.newBlockingStub(channels.createChannel("nas-orchestrator"));
    }
}
