package com.notifications.router;

import com.notifications.model.Channel;
import java.util.List;

/**
 * Owns the channel-selection decision. The orchestrator asks "where should this go" and
 * doesn't need to know why - that keeps routing rules free to evolve (new notification types,
 * new preference logic) without touching NotificationService.
 */
public interface NotificationRouter {

    List<Channel> getRoutes(String userId, String notificationType);
}
