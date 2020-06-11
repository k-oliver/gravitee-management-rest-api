/**
 * Copyright (C) 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.management.service.impl;

import io.gravitee.management.model.*;
import io.gravitee.management.service.ApiMetadataService;
import org.springframework.stereotype.Component;

import java.util.*;

import static io.gravitee.repository.management.model.MetadataReferenceType.API;
import static java.util.stream.Collectors.toList;

/**
 * @author Azize ELAMRANI (azize at graviteesource.com)
 * @author GraviteeSource Team
 */
@Component
public class ApiMetadataServiceImpl extends AbstractReferenceMetadataService implements ApiMetadataService {

    @Override
    public List<ApiMetadataEntity> findAllByApi(final String apiId) {
        final List<ReferenceMetadataEntity> allMetadata = findAllByReference(API, apiId);
        return allMetadata.stream()
                .map(m -> convert(m, apiId))
                .collect(toList());
    }

    @Override
    public ApiMetadataEntity findByIdAndApi(final String metadataId, final String apiId) {
        return convert(findByIdAndReference(metadataId, API, apiId), apiId);
    }

    @Override
    public void delete(final String metadataId, final String apiId) {
        delete(metadataId, API, apiId);
    }

    @Override
    public ApiMetadataEntity create(final NewApiMetadataEntity metadataEntity) {
        return convert(create(metadataEntity, API, metadataEntity.getApiId()), metadataEntity.getApiId());
    }

    @Override
    public ApiMetadataEntity update(final UpdateApiMetadataEntity metadataEntity) {
        return convert(update(metadataEntity, API, metadataEntity.getApiId()), metadataEntity.getApiId());
    }

    private ApiMetadataEntity convert(ReferenceMetadataEntity m, String apiId) {
        final ApiMetadataEntity apiMetadataEntity = new ApiMetadataEntity();
        apiMetadataEntity.setKey(m.getKey());
        apiMetadataEntity.setName(m.getName());
        apiMetadataEntity.setFormat(m.getFormat());
        apiMetadataEntity.setValue(m.getValue());
        apiMetadataEntity.setDefaultValue(m.getDefaultValue());
        apiMetadataEntity.setApiId(apiId);
        return apiMetadataEntity;
    }
}
