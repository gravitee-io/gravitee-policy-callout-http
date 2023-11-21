/*
 * Copyright © 2015 The Gravitee team (http://gravitee.io)
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
package test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.gravitee.gateway.core.component.ComponentProvider;
import io.gravitee.gateway.reactive.core.context.DefaultExecutionContext;
import io.gravitee.gateway.reactive.core.context.MutableRequest;
import io.gravitee.gateway.reactive.core.context.MutableResponse;

public class ExecutionContextBuilder {

    private MutableRequest request = new RequestBuilder().build();
    private final MutableResponse response = mock(MutableResponse.class);

    private final ComponentProvider componentProvider = mock(ComponentProvider.class);

    public ExecutionContextBuilder request(MutableRequest request) {
        this.request = request;
        return this;
    }

    public <T> ExecutionContextBuilder withComponent(Class<T> type, T component) {
        when(componentProvider.getComponent(type)).thenReturn(component);

        return this;
    }

    public DefaultExecutionContext build() {
        return new DefaultExecutionContext(request, response).componentProvider(componentProvider);
    }
}
