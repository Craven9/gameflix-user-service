package com.gameflix.userservice.auth;

/**
 * No @Valid constraints on purpose: a blank or missing email/password is a credential
 * failure and must get the same generic 401 as a wrong password, not a 400 that
 * describes which field was wrong.
 */
public record LoginRequest(String email, String password) {

    @Override
    public String toString() {
        return "LoginRequest[email=" + email + ", password=****]";
    }
}
