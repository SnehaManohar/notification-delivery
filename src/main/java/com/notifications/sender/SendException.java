package com.notifications.sender;

import com.notifications.model.Failure;

/** Base type for failures raised by a NotificationSender (or a decorator wrapping one). */
public abstract class SendException extends RuntimeException {

    private final Failure failure;

    protected SendException(Failure failure) {
        super(failure.reason());
        this.failure = failure;
    }

    public Failure failure() {
        return failure;
    }
}
