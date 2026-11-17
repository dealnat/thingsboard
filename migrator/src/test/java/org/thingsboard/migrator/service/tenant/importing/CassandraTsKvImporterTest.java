// SPDX-FileCopyrightText: Copyright ThingsBoard, Inc.
// SPDX-License-Identifier: BUSL-1.1
package org.thingsboard.migrator.service.tenant.importing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import org.thingsboard.migrator.utils.CassandraService;
import org.thingsboard.migrator.utils.Storage;
import org.thingsboard.migrator.utils.TestStorage;
import org.thingsboard.migrator.utils.TsKvFiles;
import org.thingsboard.migrator.utils.TsKvWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CassandraTsKvImporterTest {

    private static final UUID ENTITY_ID = UUID.randomUUID();
    private static final Pattern RESUME_POSITION = Pattern.compile("CASSANDRA_IMPORT_TS_KV_SKIP_LINES=(\\d+)");

    @TempDir
    Path workingDir;

    private Storage storage;
    private final Queue<Object[]> tsKvInserts = new ConcurrentLinkedQueue<>();
    private volatile long failingTs = -1;
    private CassandraService cassandraService;

    @BeforeEach
    void setUp() {
        storage = TestStorage.create(workingDir, "TENANT_DATA_IMPORT");
        cassandraService = Mockito.mock(CassandraService.class, invocation -> {
            if (invocation.getArgument(0) instanceof String query && query.startsWith("INSERT INTO ts_kv_cf ")) {
                Object[] args = (Object[]) invocation.getRawArguments()[1];
                if (((Number) args[4]).longValue() == failingTs) {
                    throw new IllegalStateException("insert failed");
                }
                tsKvInserts.add(args);
            }
            return null;
        });
    }

    @Test
    void skipLinesCountsAcrossFiles() throws Exception {
        List<TsKvFiles.Part> parts = writeExport(3_000);
        assertThat(parts).hasSizeGreaterThan(2);
        long skip = parts.get(0).rows() + parts.get(1).rows() + 10;

        importer(skip).run();

        assertThat(importedTs()).containsExactlyInAnyOrderElementsOf(LongStream.range(skip, 3_000).boxed().toList());
    }

    @Test
    void failedInsertStopsImportWithResumePosition() throws Exception {
        writeExport(3_000);
        failingTs = 1_500;

        assertThatThrownBy(() -> importer(0).run())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1 time-series inserts failed")
                .hasMessageContaining("CASSANDRA_IMPORT_TS_KV_SKIP_LINES=1500");
        assertThat(importedTs()).containsAll(LongStream.range(0, 1_500).boxed().toList());

        failingTs = -1;
        importer(1_500).run();
        assertThat(importedTs()).isEqualTo(LongStream.range(0, 3_000).boxed().collect(Collectors.toSet()));
    }

    @Test
    void truncatedFileStopsImportWithResumePosition() throws Exception {
        List<TsKvFiles.Part> parts = writeExport(3_000);
        Path secondFile = workingDir.resolve(parts.get(1).name() + ".gz");
        byte[] data = Files.readAllBytes(secondFile);
        Files.write(secondFile, Arrays.copyOf(data, data.length / 2));

        String error = null;
        try {
            importer(0).run();
        } catch (IllegalStateException e) {
            error = e.getMessage();
        }

        assertThat(error).isNotNull();
        Matcher matcher = RESUME_POSITION.matcher(error);
        assertThat(matcher.find()).isTrue();
        long resumePosition = Long.parseLong(matcher.group(1));
        assertThat(resumePosition).isBetween(parts.get(0).rows(), parts.get(0).rows() + parts.get(1).rows() - 1);
        // every row before the resume position is imported
        assertThat(importedTs()).containsAll(LongStream.range(0, resumePosition).boxed().toList());
    }

    @Test
    void importsNonFiniteDoubles() throws Exception {
        TsKvWriter writer = new TsKvWriter(storage, 1024 * 1024);
        writer.write(row(1, "dbl_v", Double.NaN));
        writer.write(row(2, "dbl_v", Double.POSITIVE_INFINITY));
        writer.write(row(3, "dbl_v", Double.NEGATIVE_INFINITY));
        writer.write(row(4, "dbl_v", 2.5));
        writer.finish();

        importer(0).run();

        assertThat(tsKvInserts).extracting(args -> args[5])
                .containsExactlyInAnyOrder(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 2.5);
    }

    // Rows with ts 0..rows-1 in several files
    private List<TsKvFiles.Part> writeExport(int rows) throws IOException {
        TsKvWriter writer = new TsKvWriter(storage, 20 * 1024);
        for (long ts = 0; ts < rows; ts++) {
            // poorly compressible, so that the files roll over
            writer.write(row(ts, "str_v", UUID.randomUUID().toString()));
        }
        writer.finish();
        return TsKvFiles.listForImport(storage);
    }

    private Set<Long> importedTs() {
        return tsKvInserts.stream().map(args -> ((Number) args[4]).longValue()).collect(Collectors.toSet());
    }

    private CassandraTsKvImporter importer(long skipLines) {
        CassandraTsKvImporter importer = new CassandraTsKvImporter(storage, cassandraService);
        ReflectionTestUtils.setField(importer, "tsKvTtlDays", 0);
        ReflectionTestUtils.setField(importer, "tsKvSkipLines", skipLines);
        ReflectionTestUtils.setField(importer, "parallelismLevel", 4);
        ReflectionTestUtils.setField(importer, "statsPrintInterval", 1_000);
        ReflectionTestUtils.invokeMethod(importer, "init");
        return importer;
    }

    private static Map<String, Object> row(long ts, String column, Object value) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("entity_type", "DEVICE");
        row.put("entity_id", ENTITY_ID);
        row.put("partition", 0L);
        row.put("key", "temperature");
        row.put("ts", ts);
        row.put(column, value);
        return row;
    }

}
