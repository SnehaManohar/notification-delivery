package com.notifications.sender;

import com.notifications.model.Failure;

/** A transient failure (timeout, provider 5xx, temporary unavailability) worth retrying. */
public class RetryableSendException extends SendException {

    public RetryableSendException(String reason) {
        super(Failure.retryable(reason));
    }
}
