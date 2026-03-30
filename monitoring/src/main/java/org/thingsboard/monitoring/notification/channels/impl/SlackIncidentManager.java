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

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
public class SlackIncidentManager {

    private final SlackApiClient slackApiClient;
    private final String channelId;
    private final long resolutionTimeoutSeconds;
    private final ScheduledExecutorService scheduler;

    private String activeIncidentThreadTs;
    private ScheduledFuture<?> resolutionTask;

    private final String messagePrefix;

    public SlackIncidentManager(SlackApiClient slackApiClient, String channelId, long resolutionTimeoutSeconds, String messagePrefix) {
        this.slackApiClient = slackApiClient;
        this.channelId = channelId;
        this.resolutionTimeoutSeconds = resolutionTimeoutSeconds;
        this.messagePrefix = messagePrefix;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "slack-incident-resolver");
            t.setDaemon(true);
            return t;
        });
    }

    public synchronized void sendAlert(String message) {
        if (activeIncidentThreadTs == null) {
            String incidentHeader = messagePrefix != null && !messagePrefix.isEmpty()
                    ? "Incident occurred: " + messagePrefix
                    : "Incident occurred";
            String ts = slackApiClient.postMessage(channelId, incidentHeader);
            activeIncidentThreadTs = ts;
            log.info("New incident created, thread ts: {}", ts);
        }
        slackApiClient.postThreadReply(channelId, activeIncidentThreadTs, message);
        log.debug("Alert added to incident thread {}", activeIncidentThreadTs);
        resetResolutionTimer();
    }

    private void resetResolutionTimer() {
        if (resolutionTask != null) {
            resolutionTask.cancel(false);
        }
        resolutionTask = scheduler.schedule(this::resolveIncident, resolutionTimeoutSeconds, TimeUnit.SECONDS);
    }

    private synchronized void resolveIncident() {
        if (activeIncidentThreadTs != null) {
            String incidentHeader = messagePrefix != null && !messagePrefix.isEmpty()
                    ? "Incident resolved: " + messagePrefix
                    : "Incident resolved";
            try {
                slackApiClient.postMessage(channelId, incidentHeader);
                log.info("Incident resolved (thread was {})", activeIncidentThreadTs);
            } catch (Exception e) {
                log.error("Failed to send incident resolution message", e);
            }
            activeIncidentThreadTs = null;
            resolutionTask = null;
        }
    }

    public void sendDirectMessage(String message) {
        slackApiClient.postMessage(channelId, message);
    }

    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

}
