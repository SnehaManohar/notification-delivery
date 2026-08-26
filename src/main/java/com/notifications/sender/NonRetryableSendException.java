package com.notifications.sender;

import com.notifications.model.Failure;

/** A permanent failure (invalid address, malformed request) that retrying cannot fix. */
public class NonRetryableSendException extends SendException {

    public NonRetryableSendException(String reason) {
        super(Failure.nonRetryable(reason));
    }
}
