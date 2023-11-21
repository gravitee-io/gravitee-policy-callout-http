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

import io.gravitee.gateway.api.buffer.Buffer;
import io.gravitee.gateway.api.http.HttpHeaderNames;
import io.gravitee.gateway.api.http.HttpHeaders;
import io.gravitee.gateway.reactive.core.context.AbstractRequest;
import io.gravitee.gateway.reactive.core.context.MutableRequest;

public class RequestBuilder {

    private Buffer body = Buffer.buffer();
    private final HttpHeaders headers = HttpHeaders.create();

    public static RequestBuilder aRequest() {
        return new RequestBuilder();
    }

    public RequestBuilder body(String body) {
        return body(Buffer.buffer(body));
    }

    public RequestBuilder body(Buffer body) {
        this.body = body;
        this.headers.set(HttpHeaderNames.CONTENT_LENGTH, Integer.toString(this.body.length()));
        return this;
    }

    public RequestBuilder header(String name, String value) {
        this.headers.set(name, value);
        return this;
    }

    public MutableRequest build() {
        var request = new FakeRequest();
        request.body(body);
        request.headers(headers);
        return request;
    }

    public static class FakeRequest extends AbstractRequest {

        public FakeRequest() {}

        public void headers(HttpHeaders headers) {
            this.headers = headers;
        }
    }
}
