// SPDX-FileCopyrightText: Copyright ThingsBoard, Inc.
// SPDX-License-Identifier: BUSL-1.1
package org.thingsboard.migrator.utils;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Writer of exported time-series rows in the {@link TsKvFiles} layout, safe for concurrent use.
 * With a positive max file size, each thread writes its own files, so compression runs in parallel,
 * and starts the next file once the compressed size reaches the limit (exceeded by up to the gzip buffers, tens of KB).
 * Otherwise all threads write a single legacy ts_kv.gz.
 */
public class TsKvWriter {

    private final Storage storage;
    private final long maxFileSize;
    private final AtomicInteger lastPartIndex = new AtomicInteger();
    private final ThreadLocal<File> threadPart = new ThreadLocal<>();
    private final Set<File> openParts = ConcurrentHashMap.newKeySet();
    private final List<File> closedParts = Collections.synchronizedList(new ArrayList<>());
    private final File legacyFile;

    /**
     * @param maxFileSize in bytes; 0 or less writes a single legacy ts_kv.gz without a manifest
     */
    public TsKvWriter(Storage storage, long maxFileSize) throws IOException {
        this.storage = storage;
        this.maxFileSize = maxFileSize;
        // Leftovers of a previous export would otherwise be picked up by the importer
        deleteFiles();
        legacyFile = maxFileSize > 0 ? null : new File(TsKvFiles.NAME, 0);
    }

    public void write(Map<String, Object> row) throws IOException {
        String line = storage.toLine(row);
        if (legacyFile != null) {
            synchronized (legacyFile) {
                legacyFile.write(line);
            }
            return;
        }
        File part = threadPart.get();
        if (part == null || part.compressedSize.get() >= maxFileSize) {
            if (part != null) {
                close(part);
            }
            int index = lastPartIndex.incrementAndGet();
            part = new File(TsKvFiles.partName(index), index);
            openParts.add(part);
            threadPart.set(part);
        }
        part.write(line);
    }

    /**
     * Closes all files and writes the manifest, which marks the export as complete.
     * Must be called after all writing threads are done.
     */
    public void finish() throws IOException {
        if (legacyFile != null) {
            legacyFile.writer.close();
            return;
        }
        for (File part : List.copyOf(openParts)) {
            close(part);
        }
        List<TsKvFiles.Part> parts = closedParts.stream()
                .sorted(Comparator.comparingInt(part -> part.index))
                .map(part -> new TsKvFiles.Part(part.name, part.rows))
                .toList();
        TsKvFiles.writeManifest(storage, parts);
    }

    /**
     * Closes and deletes all files of this export, so that the importer can't load it.
     * Must be called after all writing threads are done.
     */
    public void abort() throws IOException {
        if (legacyFile != null) {
            legacyFile.writer.close();
        }
        for (File part : List.copyOf(openParts)) {
            close(part);
        }
        deleteFiles();
    }

    private void close(File part) throws IOException {
        part.writer.close();
        openParts.remove(part);
        closedParts.add(part);
    }

    private void deleteFiles() throws IOException {
        for (String file : storage.listFiles(TsKvFiles.ALL)) {
            storage.delete(file);
        }
    }

    // Written by one thread at a time
    private class File {

        final String name;
        final int index;
        final AtomicLong compressedSize = new AtomicLong();
        final Writer writer;
        long rows;

        File(String name, int index) throws IOException {
            this.name = name;
            this.index = index;
            storage.newFile(name);
            writer = storage.newWriter(name, compressedSize);
        }

        void write(String line) throws IOException {
            writer.write(line);
            rows++;
        }

    }

}
