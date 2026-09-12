package com.tamojit.nasorchestrator.exception;

public class WorkspaceAlreadyExistsException extends RuntimeException {
    public WorkspaceAlreadyExistsException(String message) {
        super(message);
    }
}
