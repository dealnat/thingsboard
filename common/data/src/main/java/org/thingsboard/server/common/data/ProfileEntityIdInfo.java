/**
 * Copyright © 2016-2025 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.server.common.data;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.thingsboard.server.common.data.id.AssetId;
import org.thingsboard.server.common.data.id.AssetProfileId;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.DeviceProfileId;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.TenantId;

import java.io.Serializable;
import java.util.UUID;

@Data
@Slf4j
public class ProfileEntityIdInfo implements Serializable, HasTenantId {

    private static final long serialVersionUID = 8532058281983868003L;

    private final TenantId tenantId;
    private final EntityId profileId;
    private final EntityId entityId;

    private ProfileEntityIdInfo(UUID tenantId, EntityId profileId, EntityId entityId) {
        this.tenantId = TenantId.fromUUID(tenantId);
        this.profileId = profileId;
        this.entityId = entityId;
    }

    public static ProfileEntityIdInfo create(UUID tenantId, DeviceProfileId profileId, DeviceId entityId) {
        return new ProfileEntityIdInfo(tenantId, profileId, entityId);
    }

    public static ProfileEntityIdInfo create(UUID tenantId, AssetProfileId profileId, AssetId entityId) {
        return new ProfileEntityIdInfo(tenantId, profileId, entityId);
    }

}
