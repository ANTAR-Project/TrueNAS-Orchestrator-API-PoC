package com.tamojit.nasorchestrator.client;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Low-level TrueNAS middleware transport: authentication, GET/POST,
 * and transparent job polling for async endpoints. Knows nothing about
 * pools, datasets, or shares — purely protocol-level.
 */
@Component
public class TrueNasApiClient {
    private final RestClient restClient;

    public TrueNasApiClient(RestClient trueNasRestClient) {
        this.restClient = trueNasRestClient;
    }

    public List<Map<String, Object>> get(String path) {
        return restClient.get()
            .uri(path)
            .retrieve()
            .body(new ParameterizedTypeReference<List<Map<String, Object>>>() {
            });
    }

    public Map<String, Object> post(String path, Map<String, Object> body) {
        Object result = restClient.post()
            .uri(path)
            .body(body)
            .retrieve()
            .body(Object.class);

        if (result instanceof Number jobId) {
            return waitForJob(jobId.longValue());
        }

        if (result instanceof Map) {
            return (Map<String, Object>) result;
        }

        return Map.of("result", result);
    }

    private Map<String, Object> waitForJob(long jobId) {
        for (int i = 0; i < 60; i++) {
            List<Map<String, Object>> jobs = get("/api/v2.0/core/get_jobs");

            Map<String, Object> job = jobs == null ? null : jobs.stream()
                                                            .filter(j -> ((Number) j.get("id")).longValue() == jobId)
                                                            .findFirst().orElse(null);

            if (job != null) {
                String state = (String) job.get("state");

                if ("SUCCESS".equals(state)) {
                    Object jobResult = job.get("result");
                    if (jobResult instanceof Map) {
                        return (Map<String, Object>) jobResult;
                    }

                    Map<String, Object> wrapped = new java.util.HashMap<>();
                    wrapped.put("result", jobResult);

                    return wrapped;
                }

                if ("FAILED".equals(state) || "ABORTED".equals(state)) {
                    throw new IllegalStateException(
                        "TrueNAS job " + jobId + " failed: " + job.get("error"));
                }
            }

            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted waiting for TrueNAS job " + jobId, e);
            }
        }

        throw new IllegalStateException("Timed out waiting for TrueNAS job " + jobId);
    }
}
