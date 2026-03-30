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
package org.thingsboard.monitoring.service;

import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.thingsboard.server.common.stats.DefaultCounter;
import org.thingsboard.server.common.stats.StatsFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@RequiredArgsConstructor
public class MonitoringMetrics {

    private final StatsFactory statsFactory;

    @Value("${monitoring.rest.base_url}")
    private String targetEndpoint;

    private static final String PREFIX = "tb_monitoring";

    private final ConcurrentHashMap<String, Timer> latencyTimers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DefaultCounter> successCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DefaultCounter> failureCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> failureGauges = new ConcurrentHashMap<>();

    private DefaultCounter cycleCounter;

    @PostConstruct
    public void init() {
        cycleCounter = statsFactory.createDefaultCounter(PREFIX + ".cycles.total", "target", targetEndpoint);
    }

    public void recordLatency(String key, long nanos) {
        Timer timer = latencyTimers.computeIfAbsent(key, k ->
                statsFactory.createTimer(PREFIX + ".check.latency", "type", k, "target", targetEndpoint)
        );
        timer.record(nanos, TimeUnit.NANOSECONDS);
    }

    public void recordSuccess(String serviceKey) {
        DefaultCounter counter = successCounters.computeIfAbsent(serviceKey, k ->
                statsFactory.createDefaultCounter(PREFIX + ".check.success", "service", k, "target", targetEndpoint)
        );
        counter.increment();

        AtomicInteger gauge = getOrCreateFailureGauge(serviceKey);
        gauge.set(0);
    }

    public void recordFailure(String serviceKey) {
        DefaultCounter counter = failureCounters.computeIfAbsent(serviceKey, k ->
                statsFactory.createDefaultCounter(PREFIX + ".check.failure", "service", k, "target", targetEndpoint)
        );
        counter.increment();

        AtomicInteger gauge = getOrCreateFailureGauge(serviceKey);
        gauge.incrementAndGet();
    }

    public void recordCycle() {
        cycleCounter.increment();
    }

    private AtomicInteger getOrCreateFailureGauge(String serviceKey) {
        return failureGauges.computeIfAbsent(serviceKey, k -> {
            AtomicInteger gauge = new AtomicInteger(0);
            statsFactory.createGauge(PREFIX + ".consecutive.failures", gauge, "service", k, "target", targetEndpoint);
            return gauge;
        });
    }

}
