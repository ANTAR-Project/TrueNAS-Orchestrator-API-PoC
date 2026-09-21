package com.tamojit.videoservice.config;

import com.tamojit.grpc.filetransfer.FileTransferGrpcServiceGrpc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.GrpcChannelFactory;

@Configuration
public class GrpcClientConfig {
    @Bean
    public FileTransferGrpcServiceGrpc.FileTransferGrpcServiceStub fileTransferGrpcAsyncStub(GrpcChannelFactory channels) {
        return FileTransferGrpcServiceGrpc.newStub(channels.createChannel("nas-orchestrator"));
    }

    @Bean
    public FileTransferGrpcServiceGrpc.FileTransferGrpcServiceBlockingStub fileTransferGrpcBlockingStub(GrpcChannelFactory channels) {
        return FileTransferGrpcServiceGrpc.newBlockingStub(channels.createChannel("nas-orchestrator"));
    }
}
