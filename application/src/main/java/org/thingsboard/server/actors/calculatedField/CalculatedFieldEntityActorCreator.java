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
package org.thingsboard.server.actors.calculatedField;

import org.thingsboard.server.actors.ActorSystemContext;
import org.thingsboard.server.actors.TbActor;
import org.thingsboard.server.actors.TbActorId;
import org.thingsboard.server.actors.TbCalculatedFieldEntityActorId;
import org.thingsboard.server.actors.TbEntityActorId;
import org.thingsboard.server.actors.device.DeviceActor;
import org.thingsboard.server.actors.service.ContextBasedCreator;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.TenantId;

public class CalculatedFieldEntityActorCreator extends ContextBasedCreator {

    private final TenantId tenantId;
    private final EntityId entityId;

    public CalculatedFieldEntityActorCreator(ActorSystemContext context, TenantId tenantId, EntityId entityId) {
        super(context);
        this.tenantId = tenantId;
        this.entityId = entityId;
    }

    @Override
    public TbActorId createActorId() {
        return new TbCalculatedFieldEntityActorId(entityId);
    }

    @Override
    public TbActor createActor() {
        return new CalculatedFieldEntityActor(context, tenantId, entityId);
    }

}
