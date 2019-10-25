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
package io.gravitee.policy.callout;

import io.gravitee.common.http.HttpHeaders;
import io.vertx.core.http.HttpClientResponse;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
public class CalloutResponse {

    private final HttpClientResponse response;
    private final String content;
    private final HttpHeaders headers;

    CalloutResponse(final HttpClientResponse response) {
        this(response, null);
    }

    CalloutResponse(final HttpClientResponse response, final String content) {
        this.response = response;
        this.content = content;
        this.headers = new HttpHeaders(response.headers().size());

        response.headers().names().forEach(headerName ->
                this.headers.put(headerName, response.headers().getAll(headerName)));
    }

    public int getStatus() {
        return response.statusCode();
    }

    public HttpHeaders getHeaders() {
        return headers;
    }

    public String getContent() {
        return content;
    }
}
