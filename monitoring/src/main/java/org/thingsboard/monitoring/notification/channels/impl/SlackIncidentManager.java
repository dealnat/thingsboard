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

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
public class SlackIncidentManager {

    private final SlackApiClient slackApiClient;
    private final String channelId;
    private final long resolutionTimeoutSeconds;
    private final String messagePrefix;
    private final boolean tagChannel;
    private final ScheduledExecutorService scheduler;

    private static final Pattern BOLD_SERVICE_PATTERN = Pattern.compile("\\*([^*]+)\\*\\s*\\(");
    private static final Pattern LATENCY_KEY_PATTERN = Pattern.compile("\\[([^\\]]+Latency)]");
    private static final Pattern PLAIN_SERVICE_PATTERN = Pattern.compile("(?:^|\\s)(\\S+)\\s+-\\s+Failure:");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")
            .withZone(ZoneId.systemDefault());
    private static final Map<String, String> SERVICE_EMOJI = new LinkedHashMap<>();

    static {
        // Transport failures
        SERVICE_EMOJI.put("MQTT", ":x:");
        SERVICE_EMOJI.put("CoAP", ":x:");
        SERVICE_EMOJI.put("HTTP", ":x:");
        SERVICE_EMOJI.put("LwM2M", ":x:");
        SERVICE_EMOJI.put("EDQS", ":x:");
        // General (WS/login) failures
        SERVICE_EMOJI.put("Monitoring", ":red_circle:");
        // Latency keys
        SERVICE_EMOJI.put("wsConnectLatency", ":hourglass_flowing_sand:");
        SERVICE_EMOJI.put("wsSubscribeLatency", ":hourglass_flowing_sand:");
        SERVICE_EMOJI.put("logInLatency", ":hourglass_flowing_sand:");
        SERVICE_EMOJI.put("edqsQueryLatency", ":hourglass_flowing_sand:");
    }

    private String activeIncidentThreadTs;
    private ScheduledFuture<?> resolutionTask;
    private Instant incidentStartTime;
    private Instant lastAlertTime;
    private final Set<String> affectedServices = new LinkedHashSet<>();

    public SlackIncidentManager(SlackApiClient slackApiClient, String channelId,
                                long resolutionTimeoutSeconds, String messagePrefix, boolean tagChannel) {
        this.slackApiClient = slackApiClient;
        this.channelId = channelId;
        this.resolutionTimeoutSeconds = resolutionTimeoutSeconds;
        this.messagePrefix = messagePrefix;
        this.tagChannel = tagChannel;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "slack-incident-resolver");
            t.setDaemon(true);
            return t;
        });
    }

    public synchronized void sendAlert(String message) {
        if (activeIncidentThreadTs == null) {
            incidentStartTime = Instant.now();
            affectedServices.clear();
            affectedServices.addAll(extractServiceNames(message));
            String incidentHeader = buildIncidentMessage();
            String ts = slackApiClient.postMessage(channelId, incidentHeader);
            activeIncidentThreadTs = ts;
            log.info("New incident created, thread ts: {}", ts);
        } else {
            if (affectedServices.addAll(extractServiceNames(message))) {
                updateIncidentMessage();
            }
        }
        slackApiClient.postThreadReply(channelId, activeIncidentThreadTs, message);
        lastAlertTime = Instant.now();
        log.debug("Alert added to incident thread {}", activeIncidentThreadTs);
        resetResolutionTimer();
    }

    private String buildIncidentMessage() {
        StringBuilder sb = new StringBuilder();
        if (tagChannel) {
            sb.append("<!channel> ");
        }
        if (messagePrefix != null && !messagePrefix.isEmpty()) {
            sb.append("*").append(messagePrefix).append("*");
        }
        sb.append(" :rotating_light: Ongoing incident\n");
        if (!affectedServices.isEmpty()) {
            sb.append("Affected: ").append(formatAffectedServices());
        }
        return sb.toString();
    }

    private void updateIncidentMessage() {
        try {
            StringBuilder sb = new StringBuilder();
            if (tagChannel) {
                sb.append("<!channel> ");
            }
            if (messagePrefix != null && !messagePrefix.isEmpty()) {
                sb.append("*").append(messagePrefix).append("*");
            }
            sb.append(" :rotating_light: Ongoing incident\n");
            if (!affectedServices.isEmpty()) {
                sb.append("Affected: ").append(formatAffectedServices());
            }
            slackApiClient.updateMessage(channelId, activeIncidentThreadTs, sb.toString());
        } catch (Exception e) {
            log.error("Failed to update incident message", e);
        }
    }

    private void resetResolutionTimer() {
        if (resolutionTask != null) {
            resolutionTask.cancel(false);
        }
        resolutionTask = scheduler.schedule(this::resolveIncident, resolutionTimeoutSeconds, TimeUnit.SECONDS);
    }

    private synchronized void resolveIncident() {
        if (activeIncidentThreadTs != null) {
            try {
                // Update incident message with resolve time
                StringBuilder sb = new StringBuilder();
                if (messagePrefix != null && !messagePrefix.isEmpty()) {
                    sb.append("*").append(messagePrefix).append("*");
                }
                sb.append(" :white_check_mark: Incident resolved at: ");
                sb.append(TIME_FORMATTER.format(lastAlertTime));
                sb.append("\n");
                if (!affectedServices.isEmpty()) {
                    sb.append("Affected: ").append(formatAffectedServices()).append("\n");
                }
                slackApiClient.updateMessage(channelId, activeIncidentThreadTs, sb.toString());
                log.info("Incident resolved (thread was {})", activeIncidentThreadTs);
            } catch (Exception e) {
                log.error("Failed to send incident resolution message", e);
            }
            activeIncidentThreadTs = null;
            resolutionTask = null;
            affectedServices.clear();
        }
    }

    private String formatAffectedServices() {
        return affectedServices.stream()
                .map(name -> {
                    String emoji = SERVICE_EMOJI.get(name);
                    if (emoji == null) {
                        // Fallback for dynamic latency keys like mqttTransportRequestLatency
                        if (name.contains("Latency")) {
                            emoji = ":hourglass_flowing_sand:";
                        } else {
                            emoji = ":small_blue_diamond:";
                        }
                    }
                    return emoji + " " + name;
                })
                .collect(Collectors.joining(", "));
    }

    static Set<String> extractServiceNames(String message) {
        // Recovery messages should not contribute to affected services
        if (message.contains("is OK")) {
            return new LinkedHashSet<>();
        }
        Set<String> names = new LinkedHashSet<>();
        // Failure/Recovery: *CoAP* (coap://...) - bold service name followed by URL
        Matcher boldMatcher = BOLD_SERVICE_PATTERN.matcher(message);
        if (boldMatcher.find()) {
            names.add(boldMatcher.group(1));
            return names;
        }
        // High latency: [logInLatency] *value* - extract keys from brackets
        Matcher latencyMatcher = LATENCY_KEY_PATTERN.matcher(message);
        while (latencyMatcher.find()) {
            names.add(latencyMatcher.group(1));
        }
        if (!names.isEmpty()) {
            return names;
        }
        // Fallback: plain service name before " - Failure:"
        Matcher plainMatcher = PLAIN_SERVICE_PATTERN.matcher(message);
        if (plainMatcher.find()) {
            String name = plainMatcher.group(1);
            if (name != null) {
                names.add(name);
            }
        }
        return names;
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
