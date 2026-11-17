// SPDX-FileCopyrightText: Copyright ThingsBoard, Inc.
// SPDX-License-Identifier: BUSL-1.1
package org.thingsboard.migrator.utils;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Layout of exported time-series history in the working directory. Either:
 * - ts_kv_00001.gz, ts_kv_00002.gz, ... plus ts_kv_manifest.gz listing them with row counts, written last on success;
 * - or a single legacy ts_kv.gz without a manifest.
 */
public final class TsKvFiles {

    public static final String NAME = "ts_kv";
    public static final String MANIFEST = NAME + "_manifest";

    static final Pattern PARTS = Pattern.compile(NAME + "_\\d+");
    static final Pattern ALL = Pattern.compile(NAME + "(_\\d+|_manifest)?");

    private TsKvFiles() {}

    /**
     * @param rows null for the legacy single file
     */
    public record Part(String name, Long rows) {}

    static String partName(int index) {
        return String.format(Locale.ROOT, "%s_%05d", NAME, index);
    }

    static void writeManifest(Storage storage, List<Part> parts) throws IOException {
        storage.newFile(MANIFEST);
        try (Writer writer = storage.newWriter(MANIFEST)) {
            for (Part part : parts) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("file", part.name());
                row.put("rows", part.rows());
                storage.addToFile(writer, row);
            }
        }
    }

    public static List<Part> listForImport(Storage storage) throws IOException {
        if (storage.exists(MANIFEST)) {
            // Extracting data.tar doesn't remove files of an earlier import in the same directory
            if (storage.exists(NAME)) {
                throw new IllegalStateException("Both " + MANIFEST + ".gz and " + NAME + ".gz found in the working directory, " +
                        "one of them is left from another export. Remove the " + NAME + "*.gz files and extract the export again");
            }
            List<Part> parts = new ArrayList<>();
            storage.readAndProcess(MANIFEST, row -> parts.add(new Part((String) row.get("file"), ((Number) row.get("rows")).longValue())));
            List<String> missing = parts.stream().map(Part::name).filter(name -> !storage.exists(name)).toList();
            if (!missing.isEmpty()) {
                throw new IllegalStateException("Files listed in " + MANIFEST + ".gz are missing in the working directory: " + missing);
            }
            return parts;
        }
        if (storage.exists(NAME)) {
            return List.of(new Part(NAME, null));
        }
        if (!storage.listFiles(PARTS).isEmpty()) {
            throw new IllegalStateException(NAME + "_*.gz files found without " + MANIFEST + ".gz: the time-series export didn't complete");
        }
        throw new IllegalStateException("No time-series files in the working directory: expected " + MANIFEST + ".gz or " + NAME + ".gz");
    }

}
