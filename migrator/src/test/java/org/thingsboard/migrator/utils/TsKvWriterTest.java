// SPDX-FileCopyrightText: Copyright ThingsBoard, Inc.
// SPDX-License-Identifier: BUSL-1.1
package org.thingsboard.migrator.utils;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TsKvWriterTest {

    private static final long MAX_FILE_SIZE = 100 * 1024;

    @TempDir
    Path workingDir;

    private Storage storage;

    @BeforeEach
    void setUp() {
        storage = new Storage();
        ReflectionTestUtils.setField(storage, "workingDir", workingDir);
        ReflectionTestUtils.setField(storage, "mode", "TENANT_DATA_EXPORT");
        ReflectionTestUtils.setField(storage, "archiveResult", false);
    }

    @Test
    void rollsOverBySizeAndListsFilesInManifest() throws IOException {
        TsKvWriter writer = new TsKvWriter(storage, MAX_FILE_SIZE);
        for (int i = 0; i < 20_000; i++) {
            writer.write(row(i));
        }
        writer.finish();

        List<TsKvFiles.Part> parts = TsKvFiles.listForImport(storage);
        assertThat(parts).hasSizeGreaterThan(2);
        assertThat(parts).extracting(TsKvFiles.Part::name).startsWith("ts_kv_00001", "ts_kv_00002");
        assertThat(parts.stream().mapToLong(TsKvFiles.Part::rows).sum()).isEqualTo(20_000);
        for (TsKvFiles.Part part : parts.subList(0, parts.size() - 1)) {
            assertThat(storage.size(part.name())).isGreaterThanOrEqualTo(MAX_FILE_SIZE);
        }

        List<Long> read = new ArrayList<>();
        for (TsKvFiles.Part part : parts) {
            List<Long> partRows = readTs(part.name());
            assertThat(partRows).hasSize(part.rows().intValue());
            read.addAll(partRows);
        }
        assertThat(read).isSorted().hasSize(20_000).doesNotHaveDuplicates();
    }

    @Test
    void concurrentWritesKeepLinesIntact() throws Exception {
        TsKvWriter writer = new TsKvWriter(storage, MAX_FILE_SIZE);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            int thread = t;
            futures.add(executor.submit(() -> {
                for (int i = 0; i < 5_000; i++) {
                    writer.write(row(thread * 5_000L + i));
                }
                return null;
            }));
        }
        for (Future<?> future : futures) {
            future.get();
        }
        executor.shutdown();
        writer.finish();

        List<Long> read = new ArrayList<>();
        for (TsKvFiles.Part part : TsKvFiles.listForImport(storage)) {
            read.addAll(readTs(part.name()));
        }
        assertThat(read).hasSize(40_000).doesNotHaveDuplicates();
    }

    @Test
    void emptyExportHasManifestWithEmptyFile() throws IOException {
        TsKvWriter writer = new TsKvWriter(storage, MAX_FILE_SIZE);
        writer.finish();

        assertThat(TsKvFiles.listForImport(storage)).containsExactly(new TsKvFiles.Part("ts_kv_00001", 0L));
    }

    @Test
    void legacyModeWritesSingleFileAndRemovesPreviousExport() throws IOException {
        TsKvWriter previous = new TsKvWriter(storage, MAX_FILE_SIZE);
        previous.write(row(1));
        previous.finish();

        TsKvWriter writer = new TsKvWriter(storage, 0);
        writer.write(row(2));
        writer.write(row(3));
        writer.finish();

        assertThat(storage.exists(TsKvFiles.MANIFEST)).isFalse();
        assertThat(storage.exists("ts_kv_00001")).isFalse();
        assertThat(TsKvFiles.listForImport(storage)).containsExactly(new TsKvFiles.Part("ts_kv", null));
        assertThat(readTs("ts_kv")).containsExactly(2L, 3L);
    }

    @Test
    void abortedExportIsRejectedByImport() throws IOException {
        TsKvWriter writer = new TsKvWriter(storage, MAX_FILE_SIZE);
        writer.write(row(1));
        writer.abort();

        assertThatThrownBy(() -> TsKvFiles.listForImport(storage))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("didn't complete");
    }

    @Test
    void missingFileListedInManifestIsRejected() throws IOException {
        TsKvWriter writer = new TsKvWriter(storage, MAX_FILE_SIZE);
        writer.write(row(1));
        writer.finish();
        storage.delete("ts_kv_00001");

        assertThatThrownBy(() -> TsKvFiles.listForImport(storage))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ts_kv_00001");
    }

    @Test
    void noExportIsRejected() {
        assertThatThrownBy(() -> TsKvFiles.listForImport(storage))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No time-series files");
    }

    private List<Long> readTs(String file) throws IOException {
        List<Long> ts = new ArrayList<>();
        storage.readAndProcess(file, row -> ts.add(((Number) row.get("ts")).longValue()));
        return ts;
    }

    // Random values keep the data poorly compressible, so files reach the size limit quickly
    private static Map<String, Object> row(long ts) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("entity_type", "DEVICE");
        row.put("entity_id", UUID.randomUUID());
        row.put("partition", 0L);
        row.put("key", "temperature");
        row.put("ts", ts);
        row.put("str_v", UUID.randomUUID() + "-" + UUID.randomUUID());
        return row;
    }

}
