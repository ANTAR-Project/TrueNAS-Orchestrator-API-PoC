package com.tamojit.nasorchestrator.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/nas-orchestrator")
public class RootController {
    @Value("${server.port}")
    private String serverPort;

    @GetMapping("/health")
    public ResponseEntity<String> root() {
        return ResponseEntity.ok("Server up & Running at port: " + serverPort);
    }
}
