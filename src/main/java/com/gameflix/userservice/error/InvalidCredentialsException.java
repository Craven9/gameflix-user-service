package com.gameflix.userservice.error;

/** Any authentication failure. Maps to 401 with a generic message. */
public class InvalidCredentialsException extends RuntimeException {
    public InvalidCredentialsException(String message) {
        super(message);
    }
}
