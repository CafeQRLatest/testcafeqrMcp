package com.restaurant.pos.ai.backend;

/** A cafe-qr-backend error with a message that is safe to show to the café owner. */
public class BackendException extends RuntimeException {

    private final int status;

    public BackendException(int status, String userMessage) {
        super(userMessage);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
