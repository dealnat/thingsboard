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

import java.time.Duration;
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
    private static final Pattern PLAIN_SERVICE_PATTERN = Pattern.compile("(.+?)\\s+-\\s+Failure:");
    private static final Pattern PLAIN_RECOVERY_PATTERN = Pattern.compile("(.+?)\\s+is\\s+OK");
    private static final Pattern FAILURE_COUNT_PATTERN = Pattern.compile("number of subsequent failures:\\s*(\\d+)");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")
            .withZone(ZoneId.systemDefault());
    private String activeIncidentThreadTs;
    private ScheduledFuture<?> resolutionTask;
    private ScheduledFuture<?> durationUpdateTask;
    private Instant incidentStartTime;
    private Instant lastAlertTime;
    private final Map<String, Integer> failingServices = new LinkedHashMap<>();
    private final Set<String> recoveredServices = new LinkedHashSet<>();

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
        boolean isRecovery = message.contains("is OK");
        Set<String> serviceNames = extractServiceNames(message);

        if (activeIncidentThreadTs == null) {
            if (isRecovery) {
                return;
            }
            incidentStartTime = Instant.now();
            failingServices.clear();
            recoveredServices.clear();
            int failureCount = extractFailureCount(message);
            serviceNames.forEach(name -> failingServices.put(name, failureCount));
            String incidentHeader = buildIncidentMessage();
            String ts = slackApiClient.postMessage(channelId, incidentHeader);
            activeIncidentThreadTs = ts;
            startDurationUpdater();
            log.info("New incident created, thread ts: {}", ts);
        } else {
            boolean changed = false;
            if (isRecovery) {
                for (String name : serviceNames) {
                    if (failingServices.remove(name) != null) {
                        recoveredServices.add(name);
                        changed = true;
                    }
                }
            } else {
                int failureCount = extractFailureCount(message);
                for (String name : serviceNames) {
                    recoveredServices.remove(name);
                    failingServices.put(name, failureCount);
                    changed = true;
                }
            }
            if (changed) {
                updateIncidentMessage();
            }
        }
        slackApiClient.postThreadReply(channelId, activeIncidentThreadTs, message);
        lastAlertTime = Instant.now();
        log.debug("Alert added to incident thread {}", activeIncidentThreadTs);
        resetResolutionTimer();
    }

    private String buildOngoingMessageText() {
        StringBuilder sb = new StringBuilder();
        if (tagChannel) {
            sb.append("<!channel> ");
        }
        if (messagePrefix != null && !messagePrefix.isEmpty()) {
            sb.append("*").append(messagePrefix).append("*");
        }
        sb.append(" :rotating_light: ");
        Duration elapsed = Duration.between(incidentStartTime, Instant.now());
        if (elapsed.toMinutes() >= 1) {
            sb.append(" (").append(formatDuration(elapsed)).append(")");
        }
        sb.append(" | ");
        if (!failingServices.isEmpty() || !recoveredServices.isEmpty()) {
            sb.append(formatAffectedServices());
        }
        return sb.toString();
    }

    private String buildIncidentMessage() {
        return buildOngoingMessageText();
    }

    private void updateIncidentMessage() {
        try {
            slackApiClient.updateMessage(channelId, activeIncidentThreadTs, buildOngoingMessageText());
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

    private void startDurationUpdater() {
        if (durationUpdateTask != null) {
            durationUpdateTask.cancel(false);
        }
        durationUpdateTask = scheduler.scheduleAtFixedRate(this::updateDuration, 60, 60, TimeUnit.SECONDS);
    }

    private synchronized void updateDuration() {
        if (activeIncidentThreadTs != null) {
            updateIncidentMessage();
        }
    }

    private void stopDurationUpdater() {
        if (durationUpdateTask != null) {
            durationUpdateTask.cancel(false);
            durationUpdateTask = null;
        }
    }

    static String formatDuration(Duration duration) {
        long totalMinutes = duration.toMinutes();
        if (totalMinutes < 1) {
            return "<1m";
        } else if (totalMinutes < 60) {
            return totalMinutes + "m";
        } else {
            long hours = totalMinutes / 60;
            long minutes = totalMinutes % 60;
            return minutes > 0 ? hours + "h" + minutes + "m" : hours + "h";
        }
    }

    private synchronized void resolveIncident() {
        if (activeIncidentThreadTs != null) {
            stopDurationUpdater();
            try {
                Duration totalDuration = Duration.between(incidentStartTime, lastAlertTime);
                StringBuilder sb = new StringBuilder();
                if (messagePrefix != null && !messagePrefix.isEmpty()) {
                    sb.append("*").append(messagePrefix).append("*");
                }
                sb.append(" :white_check_mark:");
                sb.append(" (").append(formatDuration(totalDuration)).append(") | ");
                if (!failingServices.isEmpty() || !recoveredServices.isEmpty()) {
                    sb.append(formatAffectedServices()).append("\n");
                }
                slackApiClient.updateMessage(channelId, activeIncidentThreadTs, sb.toString());
                log.info("Incident resolved (thread was {})", activeIncidentThreadTs);
            } catch (Exception e) {
                log.error("Failed to send incident resolution message", e);
            }
            activeIncidentThreadTs = null;
            resolutionTask = null;
            failingServices.clear();
            recoveredServices.clear();
        }
    }

    private String formatAffectedServices() {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, Integer> entry : failingServices.entrySet()) {
            if (!first) sb.append(", ");
            String emoji = entry.getKey().contains("Latency") ? ":large_yellow_circle:" : ":red_circle:";
            sb.append(emoji).append(" ").append(entry.getKey()).append(" (").append(entry.getValue()).append(")");
            first = false;
        }
        for (String name : recoveredServices) {
            if (!first) sb.append(", ");
            sb.append(":large_green_circle: ").append(name);
            first = false;
        }
        return sb.toString();
    }

    static int extractFailureCount(String message) {

        Matcher matcher = FAILURE_COUNT_PATTERN.matcher(message);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        return 1;
    }

    Set<String> extractServiceNames(String message) {
        Set<String> names = new LinkedHashSet<>();
        // Transport messages: *CoAP* (coap://...) - bold service name followed by URL
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
        // Plain failure: "WS Connect - Failure: ..."
        Matcher plainMatcher = PLAIN_SERVICE_PATTERN.matcher(message);
        if (plainMatcher.find()) {
            String name = stripPrefix(plainMatcher.group(1));
            if (!name.isEmpty()) {
                names.add(name);
                return names;
            }
        }
        // Plain recovery: "Login is OK"
        Matcher recoveryMatcher = PLAIN_RECOVERY_PATTERN.matcher(message);
        if (recoveryMatcher.find()) {
            String name = stripPrefix(recoveryMatcher.group(1));
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return names;
    }

    private String stripPrefix(String name) {
        name = name.trim();
        if (messagePrefix != null && !messagePrefix.isEmpty() && name.startsWith(messagePrefix)) {
            name = name.substring(messagePrefix.length()).trim();
        }
        return name;
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
