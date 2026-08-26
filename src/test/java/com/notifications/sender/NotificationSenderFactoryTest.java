package com.notifications.sender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.notifications.model.Channel;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NotificationSenderFactoryTest {

    @Test
    void returnsRegisteredSenderForChannel() {
        EmailSender emailSender = new EmailSender();
        NotificationSenderFactory factory = new NotificationSenderFactory(Map.of(Channel.EMAIL, emailSender));

        assertThat(factory.getSender(Channel.EMAIL)).isSameAs(emailSender);
    }

    @Test
    void throwsForUnregisteredChannel() {
        NotificationSenderFactory factory = new NotificationSenderFactory(Map.of());

        assertThatThrownBy(() -> factory.getSender(Channel.SMS)).isInstanceOf(IllegalArgumentException.class);
    }
}
