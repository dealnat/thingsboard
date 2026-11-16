// SPDX-FileCopyrightText: Copyright ThingsBoard, Inc.
// SPDX-License-Identifier: BUSL-1.1
package org.thingsboard.migrator.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TsPartitioningTest {

    @ParameterizedTest
    @CsvSource({
            "MINUTES,    2025-11-15T10:20:30.123Z, 2025-11-15T10:20:00Z, 2025-11-15T10:21:00Z",
            "HOURS,      2025-11-15T10:20:30.123Z, 2025-11-15T10:00:00Z, 2025-11-15T11:00:00Z",
            "DAYS,       2025-11-15T10:20:30.123Z, 2025-11-15T00:00:00Z, 2025-11-16T00:00:00Z",
            "MONTHS,     2025-11-15T10:20:30.123Z, 2025-11-01T00:00:00Z, 2025-12-01T00:00:00Z",
            "MONTHS,     2025-12-31T23:59:59.999Z, 2025-12-01T00:00:00Z, 2026-01-01T00:00:00Z",
            "MONTHS,     2024-02-29T12:00:00Z,     2024-02-01T00:00:00Z, 2024-03-01T00:00:00Z",
            "MONTHS,     2025-11-01T00:00:00Z,     2025-11-01T00:00:00Z, 2025-12-01T00:00:00Z",
            "MONTHS,     2025-10-31T23:59:59.999Z, 2025-10-01T00:00:00Z, 2025-11-01T00:00:00Z",
            "YEARS,      2025-11-15T10:20:30.123Z, 2025-01-01T00:00:00Z, 2026-01-01T00:00:00Z",
            "YEARS,      2024-12-31T23:59:59.999Z, 2024-01-01T00:00:00Z, 2025-01-01T00:00:00Z",
            "MONTHS,     1969-12-15T00:00:00Z,     1969-12-01T00:00:00Z, 1970-01-01T00:00:00Z",
    })
    void partitionBoundaries(TsPartitioning partitioning, String ts, String expectedPartition, String expectedNext) {
        long partitionTs = partitioning.toPartitionTs(millis(ts));

        assertThat(partitionTs).isEqualTo(millis(expectedPartition));
        assertThat(partitioning.nextPartitionTs(partitionTs)).isEqualTo(millis(expectedNext));
    }

    @Test
    void monthsMatchesKnownCassandraPartition() {
        assertThat(TsPartitioning.MONTHS.toPartitionTs(millis("2025-11-20T08:00:00Z"))).isEqualTo(1761955200000L);
    }

    @Test
    void indefiniteIsSinglePartition() {
        assertThat(TsPartitioning.INDEFINITE.toPartitionTs(millis("2025-11-15T10:20:30Z"))).isZero();
        assertThat(TsPartitioning.INDEFINITE.toPartitionTs(0)).isZero();
        assertThat(TsPartitioning.INDEFINITE.nextPartitionTs(0)).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void parse() {
        assertThat(TsPartitioning.parse("months")).isEqualTo(TsPartitioning.MONTHS);
        assertThat(TsPartitioning.parse(" INDEFINITE ")).isEqualTo(TsPartitioning.INDEFINITE);
        assertThatThrownBy(() -> TsPartitioning.parse("WEEKS")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TsPartitioning.parse(null)).isInstanceOf(IllegalArgumentException.class);
    }

    private static long millis(String iso) {
        return Instant.parse(iso).toEpochMilli();
    }

}
