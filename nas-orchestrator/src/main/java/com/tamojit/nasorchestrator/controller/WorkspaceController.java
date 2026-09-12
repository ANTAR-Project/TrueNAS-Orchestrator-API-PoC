package com.tamojit.nasorchestrator.controller;

import com.tamojit.nasorchestrator.dto.CreateWorkspaceRequest;
import com.tamojit.nasorchestrator.dto.CreateWorkspaceResponse;
import com.tamojit.nasorchestrator.service.WorkspaceService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
@RequestMapping("/api/v1/nas-orchestrator/workspace")
@Validated
public class WorkspaceController {
    private final WorkspaceService workspaceService;

    public WorkspaceController(WorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
    }

    @PostMapping("/create")
    public ResponseEntity<CreateWorkspaceResponse> createWorkspace(@Valid @RequestBody CreateWorkspaceRequest request) throws IOException {
        return ResponseEntity.status(HttpStatus.CREATED).body(workspaceService.createWorkspace(request.name()));
    }
}
