package com.tamojit.nasorchestrator.service;

import com.tamojit.nasorchestrator.client.SmbFileClient;
import com.tamojit.nasorchestrator.dto.CreateWorkspaceResponse;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
public class WorkspaceService {
    private final SmbFileClient smbFileClient;

    public WorkspaceService(SmbFileClient smbFileClient) {
        this.smbFileClient = smbFileClient;
    }

    public CreateWorkspaceResponse createWorkspace(String name) throws IOException {
        smbFileClient.createWorkspaceDirectory(name);
        return new CreateWorkspaceResponse(name, "created");
    }
}
