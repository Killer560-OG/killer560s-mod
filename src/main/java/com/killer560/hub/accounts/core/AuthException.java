package com.killer560.hub.accounts.core;

/** Thrown when re-authenticating a Prism-stored Microsoft account fails. */
public class AuthException extends Exception {

    public AuthException(String message) {
        super(message);
    }

    public AuthException(String message, Throwable cause) {
        super(message, cause);
    }
}
