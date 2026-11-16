// SPDX-FileCopyrightText: Copyright ThingsBoard, Inc.
// SPDX-License-Identifier: BUSL-1.1
package org.thingsboard.migrator.service.tenant.exporting;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.thingsboard.migrator.MigrationService;
import org.thingsboard.migrator.Table;
import org.thingsboard.migrator.utils.TsKvWriter;
import org.thingsboard.migrator.utils.TsPartitioning;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.thingsboard.migrator.service.tenant.exporting.CassandraTsKvExporter.LATEST_KV;
import static org.thingsboard.migrator.utils.TsKvFiles.NAME;

/**
 * Exports time-series history from PostgreSQL/TimescaleDB ts_kv in the CassandraTsKvExporter files format,
 * so that CassandraTsKvImporter can load it into a hybrid setup.
 */
@Service
@RequiredArgsConstructor
@ConditionalOnExpression("'${mode}' == 'TENANT_DATA_EXPORT' and ${export.postgres_ts.enabled} == true")
@Order(2)
public class PostgresTsKvExporter extends MigrationService {

    private static final String[] VALUE_COLUMNS = {"bool_v", "str_v", "long_v", "dbl_v", "json_v"};

    private final JdbcTemplate jdbcTemplate;

    @Value("${export.postgres_ts.partitioning}")
    private String partitioningName;
    @Value("${export.postgres_ts.batch_size}")
    private int batchSize;
    @Value("${export.postgres_ts.filter.start_ts:}")
    private Long startTs;
    @Value("${export.postgres_ts.filter.end_ts:}")
    private Long endTs;
    @Value("${export.postgres.enabled}")
    private boolean postgresExportEnabled;
    @Value("${export.postgres.delay_between_queries}")
    private int delayBetweenQueries;
    @Value("${export.cassandra.enabled}")
    private boolean cassandraExportEnabled;
    @Value("${skipped_tables}")
    private Set<Table> skippedTables;
    @Value("${export.ts_kv.max_file_size_mb}")
    private long maxFileSizeMb;

    private final AtomicLong failedKeys = new AtomicLong();

    private TsPartitioning partitioning;
    private String query;
    private TsKvWriter writer;

    @PostConstruct
    private void validate() {
        if (cassandraExportEnabled) {
            throw new IllegalStateException("POSTGRES_TS_EXPORT_ENABLED requires CASSANDRA_EXPORT_ENABLED=false: both write the same " + NAME + " files");
        }
        partitioning = TsPartitioning.parse(partitioningName);
        if (batchSize <= 0) {
            throw new IllegalArgumentException("POSTGRES_TS_EXPORT_BATCH_SIZE must be positive, got " + batchSize);
        }
        if (startTs != null && endTs != null && startTs > endTs) {
            throw new IllegalArgumentException("POSTGRES_TS_EXPORT_FILTER_START_TS (" + startTs + ") is greater than POSTGRES_TS_EXPORT_FILTER_END_TS (" + endTs + ")");
        }
        if (postgresExportEnabled) {
            if (skippedTables.contains(Table.LATEST_KV)) {
                throw new IllegalStateException("Time-series keys are taken from the exported " + LATEST_KV + ", remove LATEST_KV from SKIPPED_TABLES");
            }
        } else if (!storage.exists(LATEST_KV)) {
            throw new IllegalStateException(LATEST_KV + ".gz not found in the working directory. Enable POSTGRES_EXPORT_ENABLED or put a previously exported " + LATEST_KV + ".gz there");
        }
        query = "SELECT ts, bool_v, str_v, long_v, dbl_v, json_v FROM ts_kv WHERE entity_id = ? AND key = ? AND ts > ?" +
                (endTs != null ? " AND ts <= ?" : "") + " ORDER BY ts LIMIT ?";
    }

    @Override
    protected void start() throws Exception {
        log.info("Starting PostgreSQL ts_kv export. Partitioning: {}, startTs: {}, endTs: {}. Latest KV {}",
                partitioning, startTs, endTs, storage.getFileInfo(LATEST_KV));
        reportTotal(LATEST_KV, storage.count(LATEST_KV));

        writer = new TsKvWriter(storage, maxFileSizeMb * 1024 * 1024);

        storage.readAndProcess(LATEST_KV, latestKvRow -> executor.submit(() -> {
            try {
                exportHistory(TsKey.of(latestKvRow));
            } catch (Exception e) {
                failedKeys.incrementAndGet();
                log.error("Failed to export time-series history for {} {}, key '{}'",
                        latestKvRow.get("table_name"), latestKvRow.get("entity_id"), latestKvRow.get("key_name"), e);
            }
            reportProcessed(LATEST_KV, latestKvRow);
        }));
    }

    private void exportHistory(TsKey tsKey) throws InterruptedException, IOException {
        // Keyset pagination: ts is unique per (entity_id, key)
        long cursor = startTs != null ? startTs - 1 : Long.MIN_VALUE;
        long partitionTs = Long.MIN_VALUE;
        long nextPartitionTs = Long.MIN_VALUE;
        while (true) {
            Object[] args = endTs != null ?
                    new Object[]{tsKey.entityId(), tsKey.keyId(), cursor, endTs, batchSize} :
                    new Object[]{tsKey.entityId(), tsKey.keyId(), cursor, batchSize};
            List<TsRow> rows = jdbcTemplate.query(query, (rs, rowNum) -> {
                Object[] values = new Object[VALUE_COLUMNS.length];
                for (int i = 0; i < VALUE_COLUMNS.length; i++) {
                    values[i] = rs.getObject(i + 2);
                }
                return new TsRow(rs.getLong(1), values);
            }, args);

            for (TsRow row : rows) {
                if (row.ts() < partitionTs || row.ts() >= nextPartitionTs) {
                    partitionTs = partitioning.toPartitionTs(row.ts());
                    nextPartitionTs = partitioning.nextPartitionTs(partitionTs);
                }
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("entity_type", tsKey.entityType());
                data.put("entity_id", tsKey.entityId());
                data.put("partition", partitionTs);
                data.put("key", tsKey.keyName());
                data.put("ts", row.ts());
                for (int i = 0; i < VALUE_COLUMNS.length; i++) {
                    if (row.values()[i] != null) {
                        data.put(VALUE_COLUMNS[i], row.values()[i]);
                    }
                }
                writer.write(data);
                reportProcessed(NAME, data);
            }

            if (rows.size() < batchSize) {
                return;
            }
            cursor = rows.get(rows.size() - 1).ts();
            if (delayBetweenQueries > 0) {
                TimeUnit.MILLISECONDS.sleep(delayBetweenQueries);
            }
        }
    }

    @Override
    protected void afterFinished() throws Exception {
        finishedProcessing(NAME);
        finishedProcessing(LATEST_KV);
        if (failedKeys.get() > 0) {
            writer.abort();
            throw new IllegalStateException(failedKeys.get() + " time-series keys failed to export, see errors above. The export is incomplete");
        }
        writer.finish();
    }

    private record TsKey(String entityType, UUID entityId, int keyId, String keyName) {

        static TsKey of(Map<String, Object> latestKvRow) {
            return new TsKey((String) latestKvRow.get("table_name"), (UUID) latestKvRow.get("entity_id"),
                    ((Number) latestKvRow.get("key")).intValue(), (String) latestKvRow.get("key_name"));
        }

    }

    private record TsRow(long ts, Object[] values) {}

}
