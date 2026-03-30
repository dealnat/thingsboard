/**
 * Copyright © 2016-2026 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.monitoring.notification.channels.impl;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.thingsboard.monitoring.notification.channels.NotificationChannel;

import java.time.Duration;
import java.util.Map;

@Component
@ConditionalOnProperty(value = "monitoring.notifications.slack.enabled", havingValue = "true")
@Slf4j
public class SlackNotificationChannel implements NotificationChannel {

    @Value("${monitoring.notifications.slack.webhook_url}")
    private String webhookUrl;

    @Value("${monitoring.notifications.slack.bot_token:}")
    private String botToken;

    @Value("${monitoring.notifications.slack.channel_id:}")
    private String channelId;

    @Value("${monitoring.notifications.slack.incident.enabled:false}")
    private boolean incidentEnabled;

    @Value("${monitoring.notifications.slack.incident.resolution_timeout_s:60}")
    private long resolutionTimeoutSeconds;

    @Value("${monitoring.notifications.message_prefix:}")
    private String messagePrefix;

    private RestTemplate restTemplate;
    private SlackIncidentManager incidentManager;

    @PostConstruct
    private void init() {
        restTemplate = new RestTemplateBuilder()
                .setConnectTimeout(Duration.ofSeconds(5))
                .setReadTimeout(Duration.ofSeconds(2))
                .build();

        if (incidentEnabled) {
            SlackApiClient apiClient = new SlackApiClient(botToken);
            incidentManager = new SlackIncidentManager(apiClient, channelId, resolutionTimeoutSeconds, messagePrefix);
            log.info("Slack incident mode enabled (channel: {}, resolution timeout: {}s)", channelId, resolutionTimeoutSeconds);
        }
    }

    @Override
    public void sendNotification(String message) {
        sendNotification(message, true);
    }

    @Override
    public void sendNotification(String message, boolean incident) {
        if (incidentManager != null && incident) {
            incidentManager.sendAlert(message);
        } else if (incidentManager != null) {
            incidentManager.sendDirectMessage(message);
        } else {
            restTemplate.postForObject(webhookUrl, Map.of("text", message), String.class);
        }
    }

    @PreDestroy
    private void destroy() {
        if (incidentManager != null) {
            incidentManager.shutdown();
        }
    }

}
