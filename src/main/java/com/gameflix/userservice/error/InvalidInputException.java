package com.gameflix.userservice.error;

/** Input that passes annotation validation but is still invalid. Maps to 400. */
public class InvalidInputException extends RuntimeException {
    public InvalidInputException(String message) {
        super(message);
    }
}
