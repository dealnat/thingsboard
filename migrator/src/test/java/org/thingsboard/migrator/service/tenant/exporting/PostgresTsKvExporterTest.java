// SPDX-FileCopyrightText: Copyright ThingsBoard, Inc.
// SPDX-License-Identifier: BUSL-1.1
package org.thingsboard.migrator.service.tenant.exporting;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.thingsboard.migrator.utils.Storage;
import org.thingsboard.migrator.utils.TestStorage;
import org.thingsboard.migrator.utils.TsKvFiles;
import org.thingsboard.migrator.utils.TsKvWriterFactory;
import org.thingsboard.migrator.utils.TsPartitioning;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class PostgresTsKvExporterTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18");

    private static final UUID DEVICE = UUID.fromString("d1000000-0000-1000-8000-000000000001");
    private static final UUID OTHER_DEVICE = UUID.fromString("d2000000-0000-1000-8000-000000000002");
    private static final long OCT_31_23_58 = 1761955080000L;
    private static final long NOV_1 = 1761955200000L;
    private static final long OCT_1 = 1759276800000L;

    private static JdbcTemplate jdbcTemplate;

    @TempDir
    Path workingDir;

    private Storage storage;

    @BeforeAll
    static void createDatabase() {
        jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        jdbcTemplate.execute("""
                CREATE TABLE key_dictionary (key varchar(255) NOT NULL, key_id serial UNIQUE, CONSTRAINT key_dictionary_id_pkey PRIMARY KEY (key));
                CREATE TABLE ts_kv (entity_id uuid NOT NULL, key int NOT NULL, ts bigint NOT NULL, bool_v boolean, str_v varchar(10000000),
                    long_v bigint, dbl_v double precision, json_v json, CONSTRAINT ts_kv_pkey PRIMARY KEY (entity_id, key, ts)) PARTITION BY RANGE (ts);
                CREATE TABLE ts_kv_2025_10 PARTITION OF ts_kv FOR VALUES FROM (1759276800000) TO (1761955200000);
                CREATE TABLE ts_kv_2025_11 PARTITION OF ts_kv FOR VALUES FROM (1761955200000) TO (1764547200000);
                CREATE TABLE ts_kv_default PARTITION OF ts_kv DEFAULT;
                -- ids differ from the ones in ts_kv_latest.gz written by the tests
                INSERT INTO key_dictionary (key) VALUES ('unused1'), ('unused2'), ('temperature'), ('status'), ('active'), ('counter'), ('payload'), ('orphan');
                CREATE FUNCTION k(name text) RETURNS int AS $$ SELECT key_id FROM key_dictionary WHERE key = name $$ LANGUAGE sql;
                """);
        // 5 points crossing the October/November partitions, with non-finite values
        jdbcTemplate.update("INSERT INTO ts_kv (entity_id, key, ts, dbl_v) SELECT ?, k('temperature'), ? + g * 60000, " +
                "(ARRAY['1.5', 'NaN', 'Infinity', '-Infinity', '2.5']::float8[])[g + 1] FROM generate_series(0, 4) g", DEVICE, OCT_31_23_58);
        // timestamps at the long bounds land in the DEFAULT partition
        jdbcTemplate.update("INSERT INTO ts_kv (entity_id, key, ts, dbl_v) VALUES (?, k('temperature'), ?, 0), (?, k('temperature'), ?, 0)",
                DEVICE, Long.MIN_VALUE, DEVICE, Long.MAX_VALUE);
        jdbcTemplate.update("INSERT INTO ts_kv (entity_id, key, ts, str_v) VALUES (?, k('status'), ?, 'ünïcödé \"q\"')", DEVICE, NOV_1);
        jdbcTemplate.update("INSERT INTO ts_kv (entity_id, key, ts, bool_v) VALUES (?, k('active'), ?, true)", DEVICE, NOV_1);
        jdbcTemplate.update("INSERT INTO ts_kv (entity_id, key, ts, long_v) VALUES (?, k('counter'), ?, 9000000000)", DEVICE, NOV_1);
        jdbcTemplate.update("INSERT INTO ts_kv (entity_id, key, ts, json_v) VALUES (?, k('payload'), ?, '{\"a\":1}')", DEVICE, NOV_1);
        // not exported: no ts_kv_latest row
        jdbcTemplate.update("INSERT INTO ts_kv (entity_id, key, ts, long_v) VALUES (?, k('orphan'), ?, 1)", DEVICE, NOV_1);
        jdbcTemplate.update("INSERT INTO ts_kv (entity_id, key, ts, dbl_v) VALUES (?, k('temperature'), ?, 1)", OTHER_DEVICE, NOV_1);
    }

    @BeforeEach
    void setUp() {
        storage = TestStorage.create(workingDir, "TENANT_DATA_EXPORT");
    }

    @Test
    void exportsHistoryOfLatestKeys() throws Exception {
        writeLatest(latest(DEVICE, "temperature"), latest(DEVICE, "status"), latest(DEVICE, "active"), latest(DEVICE, "counter"),
                latest(DEVICE, "payload"), latest(DEVICE, "missing_in_dictionary"));

        exporter(null, null).run();

        List<Map<String, Object>> rows = readExport();
        assertThat(rows).hasSize(11);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row).containsEntry("entity_type", "DEVICE").containsEntry("entity_id", DEVICE);
            assertThat(List.copyOf(row.keySet()).subList(0, 5)).containsExactly("entity_type", "entity_id", "partition", "key", "ts");
        });
        assertThat(values(rows, "temperature", "dbl_v")).containsExactly(0.0, 1.5, "NaN", "Infinity", "-Infinity", 2.5, 0.0);
        assertThat(partitions(rows, "temperature")).containsExactly(Long.MIN_VALUE, OCT_1, OCT_1, NOV_1, NOV_1, NOV_1,
                TsPartitioning.MONTHS.toPartitionTs(Long.MAX_VALUE));
        assertThat(values(rows, "status", "str_v")).containsExactly("ünïcödé \"q\"");
        assertThat(values(rows, "active", "bool_v")).containsExactly(true);
        assertThat(values(rows, "counter", "long_v")).containsExactly(9000000000L);
        assertThat(values(rows, "payload", "json_v")).containsExactly("{\"a\":1}");
    }

    @Test
    void filterBoundsAreInclusive() throws Exception {
        writeLatest(latest(DEVICE, "temperature"));

        exporter(OCT_31_23_58 + 60000, NOV_1 + 60000).run();

        assertThat(readExport()).extracting(row -> ((Number) row.get("ts")).longValue())
                .containsExactlyInAnyOrder(OCT_31_23_58 + 60000, NOV_1, NOV_1 + 60000);
    }

    @Test
    void failedKeyAbortsExport() throws IOException {
        Map<String, Object> broken = latest(DEVICE, "status");
        broken.put("entity_id", "not-a-uuid");
        writeLatest(latest(DEVICE, "temperature"), broken);

        assertThatThrownBy(() -> exporter(null, null).run())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1 time-series keys failed to export");
        assertThat(storage.listFiles(Pattern.compile("ts_kv(_\\d+|_manifest)?"))).isEmpty();
    }

    @Test
    void poolSmallerThanParallelismIsRejected() throws IOException {
        writeLatest(latest(DEVICE, "temperature"));
        try (HikariDataSource dataSource = new HikariDataSource()) {
            dataSource.setMaximumPoolSize(4);
            PostgresTsKvExporter exporter = new PostgresTsKvExporter(new JdbcTemplate(dataSource), writerFactory());
            configure(exporter, null, null);

            assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(exporter, "validate"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("DATABASE_MAX_POOL_SIZE (4) must be greater than PARALLELISM_LEVEL (4)");
        }
    }

    private PostgresTsKvExporter exporter(Long startTs, Long endTs) {
        PostgresTsKvExporter exporter = new PostgresTsKvExporter(jdbcTemplate, writerFactory());
        configure(exporter, startTs, endTs);
        ReflectionTestUtils.invokeMethod(exporter, "init");
        ReflectionTestUtils.invokeMethod(exporter, "validate");
        return exporter;
    }

    private void configure(PostgresTsKvExporter exporter, Long startTs, Long endTs) {
        ReflectionTestUtils.setField(exporter, "storage", storage);
        ReflectionTestUtils.setField(exporter, "parallelismLevel", 4);
        ReflectionTestUtils.setField(exporter, "statsPrintInterval", 1_000);
        ReflectionTestUtils.setField(exporter, "partitioningName", "MONTHS");
        // pages of 2 rows exercise the keyset pagination
        ReflectionTestUtils.setField(exporter, "batchSize", 2);
        ReflectionTestUtils.setField(exporter, "startTs", startTs);
        ReflectionTestUtils.setField(exporter, "endTs", endTs);
        ReflectionTestUtils.setField(exporter, "postgresExportEnabled", false);
        ReflectionTestUtils.setField(exporter, "delayBetweenQueries", 0);
        ReflectionTestUtils.setField(exporter, "cassandraExportEnabled", false);
        ReflectionTestUtils.setField(exporter, "skippedTables", Set.of());
    }

    private TsKvWriterFactory writerFactory() {
        TsKvWriterFactory factory = new TsKvWriterFactory(storage);
        ReflectionTestUtils.setField(factory, "maxFileSizeMb", 1L);
        return factory;
    }

    // As written by PostgresTenantDataExporter; the key ids are wrong on purpose
    private static Map<String, Object> latest(UUID entityId, String keyName) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("table_name", "DEVICE");
        row.put("entity_id", entityId);
        row.put("key", 1000);
        row.put("ts", NOV_1);
        row.put("key_name", keyName);
        return row;
    }

    @SafeVarargs
    private void writeLatest(Map<String, Object>... rows) throws IOException {
        storage.newFile(CassandraTsKvExporter.LATEST_KV);
        try (Writer writer = storage.newWriter(CassandraTsKvExporter.LATEST_KV)) {
            for (Map<String, Object> row : rows) {
                storage.addToFile(writer, row);
            }
        }
    }

    private List<Map<String, Object>> readExport() throws IOException {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (TsKvFiles.Part part : TsKvFiles.listForImport(storage)) {
            storage.readAndProcess(part.name(), rows::add);
        }
        rows.sort((a, b) -> Long.compare(((Number) a.get("ts")).longValue(), ((Number) b.get("ts")).longValue()));
        return rows;
    }

    private static List<Object> values(List<Map<String, Object>> rows, String key, String column) {
        return rows.stream().filter(row -> key.equals(row.get("key"))).map(row -> row.get(column)).toList();
    }

    private static List<Long> partitions(List<Map<String, Object>> rows, String key) {
        return rows.stream().filter(row -> key.equals(row.get("key"))).map(row -> ((Number) row.get("partition")).longValue()).toList();
    }

}
