package com.example.cloudapi;

/** Failure while constructing, sending, or decoding an encrypted API request. */
public class CloudApiException extends Exception {
    public CloudApiException(String message) {
        super(message);
    }

    public CloudApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
