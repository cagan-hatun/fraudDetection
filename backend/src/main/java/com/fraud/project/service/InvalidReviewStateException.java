package com.fraud.project.service;

/**
 * REVIEW durumunda olmayan bir işlem için karar gönderilmeye çalışıldığında.
 * GlobalExceptionHandler bunu 409 Conflict'e çeviriyor.
 */
public class InvalidReviewStateException extends RuntimeException {

    public InvalidReviewStateException(String message) {
        super(message);
    }
}
