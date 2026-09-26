package com.tamojit.nasorchestrator.service;

import com.tamojit.nasorchestrator.client.SmbFileClient;
import com.tamojit.nasorchestrator.client.TrueNasStorageClient;
import com.tamojit.nasorchestrator.exception.SmbOperationException;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
@ConditionalOnProperty(name = "smb.bootstrap.enabled", havingValue = "true")
public class StorageBoostrapService {
    private static final Logger log = LoggerFactory.getLogger(StorageBoostrapService.class);
    private final TrueNasStorageClient trueNasStorageClient;
    private final SmbFileClient smbFileClient;

    @Value("${smb.bootstrap.pool-name}")
    private String poolName;

    @Value("${smb.bootstrap.disk-identifier}")
    private String diskIdentifier;

    @Value("${smb.bootstrap.dataset-name}")
    private String datasetName;

    @Value("${smb.bootstrap.share-name}")
    private String shareName;

    @Value("${smb.username}")
    private String smbUsername;

    @Value("${smb.full-name}")
    private String smbFullName;

    @Value("${smb.password}")
    private String smbPassword;

    public StorageBoostrapService(
        TrueNasStorageClient trueNasStorageClient,
        SmbFileClient smbFileClient
    ) {
        this.trueNasStorageClient = trueNasStorageClient;
        this.smbFileClient = smbFileClient;
    }

    @PostConstruct
    public void provisionBaselineStorage() {
        if (!trueNasStorageClient.userExists(smbUsername)) {
            log.info("Creating Baseline storage for user {}", smbUsername);
            trueNasStorageClient.createUser(smbUsername, smbFullName, smbPassword);
        }

        if (!trueNasStorageClient.poolExists(poolName)) {
            log.info("Creating pool {}", poolName);
            trueNasStorageClient.createPool(poolName, diskIdentifier);
        }

        if (!trueNasStorageClient.datasetExists(poolName, datasetName)) {
            log.info("Creating dataset {}", datasetName);
            trueNasStorageClient.createDataset(poolName, datasetName);
        }

        String datasetPath = "/mnt/" + poolName + "/" + datasetName;
        trueNasStorageClient.setDatasetPermissions(datasetPath, null, null, "777");

        if (!trueNasStorageClient.smbShareExists(shareName)) {
            log.info("Creating share {}", shareName);
            trueNasStorageClient.createSmbShare(datasetPath, shareName);
        }
        trueNasStorageClient.startService("cifs");

        try {
            if (!smbFileClient.workspaceExists("shared")) {
                log.info("Creating shared workspace");
                smbFileClient.createWorkspaceDirectory("shared");
            }
        } catch (IOException e) {
            throw new SmbOperationException("Failed to bootstrap shared workspace: " + e.getMessage(), e);
        }

        log.info("Baseline SMB storage provisioned: user={}, pool={}, dataset={}, share={}", smbUsername, poolName, datasetName, shareName);
    }
}
