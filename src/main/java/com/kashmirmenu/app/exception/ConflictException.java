package com.kashmirmenu.app.exception;

/** The request is valid but cannot be done in the current state (409). */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
