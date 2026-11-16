// SPDX-FileCopyrightText: Copyright ThingsBoard, Inc.
// SPDX-License-Identifier: BUSL-1.1
package org.thingsboard.migrator.utils;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Thread-safe writer of exported time-series rows in the {@link TsKvFiles} layout.
 * With a positive max file size, starts the next file once the current one reaches it on disk.
 * The size is checked after the gzip and file buffers are flushed, so a file may exceed it by a few hundred KB.
 */
public class TsKvWriter {

    private static final int SIZE_CHECK_INTERVAL_ROWS = 1000;

    private final Storage storage;
    private final long maxFileSize;
    private final List<TsKvFiles.Part> parts = new ArrayList<>();

    private String fileName;
    private Writer writer;
    private long rows;

    /**
     * @param maxFileSize in bytes; 0 or less writes a single legacy ts_kv.gz without a manifest
     */
    public TsKvWriter(Storage storage, long maxFileSize) throws IOException {
        this.storage = storage;
        this.maxFileSize = maxFileSize;
        // Leftovers of a previous export would otherwise be picked up by the importer
        for (String file : storage.listFiles(TsKvFiles.ALL)) {
            storage.delete(file);
        }
        openFile();
    }

    public void write(Map<String, Object> row) throws IOException {
        String line = storage.toLine(row);
        synchronized (this) {
            if (isRolling() && rows > 0 && rows % SIZE_CHECK_INTERVAL_ROWS == 0 && storage.size(fileName) >= maxFileSize) {
                closeFile();
                openFile();
            }
            writer.write(line);
            rows++;
        }
    }

    /**
     * Closes the output and writes the manifest, which marks the export as complete.
     */
    public synchronized void finish() throws IOException {
        closeFile();
        if (isRolling()) {
            TsKvFiles.writeManifest(storage, parts);
        }
    }

    /**
     * Closes the output without the manifest, so the importer rejects the incomplete export.
     */
    public synchronized void abort() throws IOException {
        writer.close();
    }

    private boolean isRolling() {
        return maxFileSize > 0;
    }

    private void openFile() throws IOException {
        fileName = isRolling() ? TsKvFiles.partName(parts.size() + 1) : TsKvFiles.NAME;
        storage.newFile(fileName);
        writer = storage.newWriter(fileName);
        rows = 0;
    }

    private void closeFile() throws IOException {
        writer.close();
        parts.add(new TsKvFiles.Part(fileName, rows));
    }

}
