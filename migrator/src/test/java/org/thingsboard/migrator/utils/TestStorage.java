// SPDX-FileCopyrightText: Copyright ThingsBoard, Inc.
// SPDX-License-Identifier: BUSL-1.1
package org.thingsboard.migrator.utils;

import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;

public final class TestStorage {

    private TestStorage() {}

    public static Storage create(Path workingDir, String mode) {
        Storage storage = new Storage();
        ReflectionTestUtils.setField(storage, "workingDir", workingDir);
        ReflectionTestUtils.setField(storage, "mode", mode);
        ReflectionTestUtils.setField(storage, "archiveResult", false);
        return storage;
    }

}
