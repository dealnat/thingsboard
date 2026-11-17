// SPDX-FileCopyrightText: Copyright ThingsBoard, Inc.
// SPDX-License-Identifier: BUSL-1.1
package org.thingsboard.migrator.utils;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class TsKvWriterFactory {

    private final Storage storage;

    @Value("${export.ts_kv.max_file_size_mb}")
    private long maxFileSizeMb;

    @PostConstruct
    private void validate() {
        if (maxFileSizeMb < 0) {
            throw new IllegalArgumentException("TS_KV_EXPORT_MAX_FILE_SIZE_MB must be 0 or positive, got " + maxFileSizeMb);
        }
    }

    public TsKvWriter create() throws IOException {
        return new TsKvWriter(storage, maxFileSizeMb * 1024 * 1024);
    }

}
