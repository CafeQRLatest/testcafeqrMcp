package com.restaurant.pos.ai.tools;

/** Bad tool arguments; the message is returned to the AI model so it can correct the call. */
public class ToolInputException extends RuntimeException {
    public ToolInputException(String message) {
        super(message);
    }
}
