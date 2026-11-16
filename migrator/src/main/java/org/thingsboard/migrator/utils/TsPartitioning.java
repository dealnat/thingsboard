// SPDX-FileCopyrightText: Copyright ThingsBoard, Inc.
// SPDX-License-Identifier: BUSL-1.1
package org.thingsboard.migrator.utils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;

/**
 * Mirrors NoSqlTsPartitionDate and CassandraBaseTimeseriesDao.toPartitionTs from the dao module,
 * which the migrator doesn't depend on.
 */
public enum TsPartitioning {

    MINUTES, HOURS, DAYS, MONTHS, YEARS, INDEFINITE;

    public long toPartitionTs(long ts) {
        LocalDateTime time = LocalDateTime.ofInstant(Instant.ofEpochMilli(ts), ZoneOffset.UTC);
        LocalDateTime partition = switch (this) {
            case MINUTES -> time.truncatedTo(ChronoUnit.MINUTES);
            case HOURS -> time.truncatedTo(ChronoUnit.HOURS);
            case DAYS -> time.truncatedTo(ChronoUnit.DAYS);
            case MONTHS -> time.truncatedTo(ChronoUnit.DAYS).withDayOfMonth(1);
            case YEARS -> time.truncatedTo(ChronoUnit.DAYS).withDayOfYear(1);
            case INDEFINITE -> LocalDateTime.ofEpochSecond(0, 0, ZoneOffset.UTC);
        };
        return partition.toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    public long nextPartitionTs(long partitionTs) {
        if (this == INDEFINITE) {
            return Long.MAX_VALUE;
        }
        LocalDateTime time = LocalDateTime.ofInstant(Instant.ofEpochMilli(partitionTs), ZoneOffset.UTC);
        LocalDateTime next = switch (this) {
            case MINUTES -> time.plusMinutes(1);
            case HOURS -> time.plusHours(1);
            case DAYS -> time.plusDays(1);
            case MONTHS -> time.plusMonths(1);
            case YEARS -> time.plusYears(1);
            case INDEFINITE -> throw new IllegalStateException();
        };
        return next.toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    public static TsPartitioning parse(String name) {
        return Arrays.stream(values())
                .filter(p -> p.name().equalsIgnoreCase(name == null ? null : name.trim()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported ts partitioning '" + name + "', expected one of " + Arrays.toString(values())));
    }

}
