package com.tamojit.streamingservice.dto;

public record ValidationOutcome(
    boolean valid,
    boolean reachable,
    String username
) {
}
