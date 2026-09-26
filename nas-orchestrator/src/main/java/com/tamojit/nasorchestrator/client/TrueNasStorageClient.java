package com.tamojit.nasorchestrator.client;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class TrueNasStorageClient {
    private final TrueNasApiClient trueNasApiClient;

    public TrueNasStorageClient(TrueNasApiClient trueNasApiClient) {
        this.trueNasApiClient = trueNasApiClient;
    }

    public boolean poolExists(String poolName) {
        return trueNasApiClient.get("/api/v2.0/pool").stream()
            .anyMatch(p -> poolName.equals(p.get("name")));
    }

    public void createPool(String poolName, String diskIdentifier) {
        trueNasApiClient.post("/api/v2.0/pool", Map.of(
            "name", poolName,
            "encryption", false,
            "topology", Map.of(
                "data", List.of(Map.of(
                    "type", "STRIPE",
                    "disks", List.of(diskIdentifier)
                ))
            ),
            "allow_duplicate_serials", true
        ));
    }

    public boolean datasetExists(String poolName, String datasetName) {
        String id = poolName + "/" + datasetName;
        return trueNasApiClient.get("/api/v2.0/pool/dataset").stream()
            .anyMatch(d -> id.equals(d.get("id")));
    }

    public void createDataset(String poolName, String datasetName) {
        trueNasApiClient.post("/api/v2.0/pool/dataset", Map.of(
            "name", poolName + "/" + datasetName
        ));
    }

    public boolean smbShareExists(String smbShareName) {
        return trueNasApiClient.get("/api/v2.0/sharing/smb").stream()
            .anyMatch(smb -> smbShareName.equals(smb.get("name")));
    }

    public void createSmbShare(String datasetPath, String smbShareName) {
        trueNasApiClient.post("/api/v2.0/sharing/smb", Map.of(
            "path", datasetPath,
            "name", smbShareName,
            "purpose", "DEFAULT_SHARE",
            "enabled", true
        ));
    }

    public void startService(String serviceName) {
        trueNasApiClient.post("/api/v2.0/service/start", Map.of(
            "service", serviceName
        ));
    }

    public void setDatasetPermissions(String path, Integer uid, Integer gid, String mode) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("path", path);
        body.put("uid", uid);
        body.put("gid", gid);
        body.put("mode", mode);
        body.put("options", Map.of("stripacl", true, "recursive", true));

        trueNasApiClient.post("/api/v2.0/filesystem/setperm", body);
    }

    public boolean userExists(String username) {
        return trueNasApiClient.get("/api/v2.0/user").stream()
            .anyMatch(user -> username.equals(user.get("username")));
    }

    public void createUser(String username, String fullName, String password) {
        trueNasApiClient.post("/api/v2.0/user", Map.of(
            "username", username,
            "full_name", fullName,
            "password", password,
            "group_create", true,
            "smb", true
        ));
    }
}
