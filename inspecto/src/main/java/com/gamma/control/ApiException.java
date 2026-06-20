package com.gamma.control;

/** Maps to an HTTP status + JSON {@code {"error": …}} body. */
final class ApiException extends RuntimeException {
    final int status;

    ApiException(int status, String message) {
        super(message);
        this.status = status;
    }
}
