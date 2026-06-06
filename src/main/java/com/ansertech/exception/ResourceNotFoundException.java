package com.ansertech.exception;

public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String message) {
        super(message);
    }
    public ResourceNotFoundException(String entity, Long id) {
        super(entity + " no encontrado con id: " + id);
    }
}
