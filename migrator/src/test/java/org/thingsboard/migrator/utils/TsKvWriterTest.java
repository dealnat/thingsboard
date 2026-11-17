// SPDX-FileCopyrightText: Copyright ThingsBoard, Inc.
// SPDX-License-Identifier: BUSL-1.1
package org.thingsboard.migrator.utils;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
        storage = TestStorage.create(workingDir, "TENANT_DATA_EXPORT");
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
            assertThat(storage.size(part.name())).isBetween(MAX_FILE_SIZE, MAX_FILE_SIZE + 64 * 1024);
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
    void eachThreadWritesItsOwnFiles() throws Exception {
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

        List<TsKvFiles.Part> parts = TsKvFiles.listForImport(storage);
        assertThat(parts).extracting(TsKvFiles.Part::name).isSorted();
        List<Long> read = new ArrayList<>();
        for (TsKvFiles.Part part : parts) {
            List<Long> partRows = readTs(part.name());
            assertThat(partRows).hasSize(part.rows().intValue());
            // rows of a file come from one thread, in its order
            assertThat(partRows).isSorted();
            assertThat(partRows.get(partRows.size() - 1) / 5_000).isEqualTo(partRows.get(0) / 5_000);
            read.addAll(partRows);
        }
        assertThat(read).hasSize(40_000).doesNotHaveDuplicates();
    }

    @Test
    void emptyExportHasEmptyManifest() throws IOException {
        TsKvWriter writer = new TsKvWriter(storage, MAX_FILE_SIZE);
        writer.finish();

        assertThat(TsKvFiles.listForImport(storage)).isEmpty();
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
    void abortDeletesAllFiles() throws IOException {
        TsKvWriter writer = new TsKvWriter(storage, MAX_FILE_SIZE);
        for (int i = 0; i < 5_000; i++) {
            writer.write(row(i));
        }
        writer.abort();

        assertThat(storage.listFiles(TsKvFiles.ALL)).isEmpty();
        assertThatThrownBy(() -> TsKvFiles.listForImport(storage)).hasMessageContaining("No time-series files");
    }

    @Test
    void abortDeletesLegacyFile() throws IOException {
        TsKvWriter writer = new TsKvWriter(storage, 0);
        writer.write(row(1));
        writer.abort();

        assertThat(storage.listFiles(TsKvFiles.ALL)).isEmpty();
        assertThatThrownBy(() -> TsKvFiles.listForImport(storage)).hasMessageContaining("No time-series files");
    }

    @Test
    void filesWithoutManifestAreRejected() throws IOException {
        // An export that crashed before finish()
        TsKvWriter writer = new TsKvWriter(storage, MAX_FILE_SIZE);
        writer.write(row(1));

        assertThatThrownBy(() -> TsKvFiles.listForImport(storage))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("didn't complete");
    }

    @Test
    void manifestAndLegacyFileTogetherAreRejected() throws IOException {
        TsKvWriter writer = new TsKvWriter(storage, MAX_FILE_SIZE);
        writer.write(row(1));
        writer.finish();
        storage.newFile(TsKvFiles.NAME);
        try (Writer legacy = storage.newWriter(TsKvFiles.NAME)) {
            storage.addToFile(legacy, row(2));
        }

        assertThatThrownBy(() -> TsKvFiles.listForImport(storage))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("left from another export");
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

    @Test
    void partNamesUseAsciiDigitsInAnyLocale() {
        Locale defaultLocale = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("th-TH-u-nu-thai"));
            assertThat(TsKvFiles.partName(12)).isEqualTo("ts_kv_00012");
        } finally {
            Locale.setDefault(defaultLocale);
        }
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
