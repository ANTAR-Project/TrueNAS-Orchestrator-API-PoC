package com.tamojit.videoservice.dto;

public record ValidationOutcome(
    boolean valid,
    boolean reachable,
    String username
) {
}
