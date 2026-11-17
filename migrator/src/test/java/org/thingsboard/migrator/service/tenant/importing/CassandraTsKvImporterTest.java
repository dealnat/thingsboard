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

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

class CassandraTsKvImporterTest {

    private static final UUID ENTITY_ID = UUID.randomUUID();

    @TempDir
    Path workingDir;

    private Storage storage;
    private final Queue<Object[]> tsKvInserts = new ConcurrentLinkedQueue<>();
    private CassandraService cassandraService;

    @BeforeEach
    void setUp() {
        storage = TestStorage.create(workingDir, "TENANT_DATA_IMPORT");
        cassandraService = Mockito.mock(CassandraService.class, invocation -> {
            if (invocation.getArgument(0) instanceof String query && query.startsWith("INSERT INTO ts_kv_cf ")) {
                tsKvInserts.add((Object[]) invocation.getRawArguments()[1]);
            }
            return null;
        });
    }

    @Test
    void skipLinesCountsAcrossFiles() throws Exception {
        TsKvWriter writer = new TsKvWriter(storage, 20 * 1024);
        for (long ts = 0; ts < 3_000; ts++) {
            // poorly compressible, so that the files roll over
            writer.write(row(ts, "str_v", UUID.randomUUID().toString()));
        }
        writer.finish();
        List<TsKvFiles.Part> parts = TsKvFiles.listForImport(storage);
        assertThat(parts).hasSizeGreaterThan(2);
        long skip = parts.get(0).rows() + parts.get(1).rows() + 10;

        importer(skip).run();

        assertThat(tsKvInserts).extracting(args -> ((Number) args[4]).longValue())
                .containsExactlyInAnyOrderElementsOf(LongStream.range(skip, 3_000).boxed().toList());
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
