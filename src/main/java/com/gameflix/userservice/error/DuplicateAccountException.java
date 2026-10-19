package com.gameflix.userservice.error;

/** Thrown when the database unique constraint on email or username rejects an insert. Maps to 409. */
public class DuplicateAccountException extends RuntimeException {
    public DuplicateAccountException(String message) {
        super(message);
    }
}
