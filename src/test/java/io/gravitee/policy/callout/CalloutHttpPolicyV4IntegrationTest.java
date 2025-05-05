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
package io.gravitee.policy.callout;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.gravitee.apim.gateway.tests.sdk.annotations.DeployApi;
import io.gravitee.common.http.MediaType;
import io.gravitee.gateway.api.http.HttpHeaderNames;
import io.reactivex.rxjava3.subscribers.TestSubscriber;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.rxjava3.core.buffer.Buffer;
import io.vertx.rxjava3.core.http.HttpClient;
import io.vertx.rxjava3.core.http.HttpClientRequest;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CalloutHttpPolicyV4IntegrationTest {

    @Nested
    class HttpProxy extends AbstractV4EngineTest {

        @ParameterizedTest
        @DeployApi({ "/apis/v4/proxy/request-callout-http.json", "/apis/v4/proxy/response-callout-http.json" })
        @ValueSource(strings = { "/proxy-request-callout-http", "/proxy-response-callout-http" })
        void should_do_callout_and_process_response(String path, HttpClient client) {
            wiremock.stubFor(get("/endpoint").willReturn(ok("response from backend")));
            calloutServer.stubFor(get("/callout").willReturn(ok("response from callout")));

            var obs = client
                .rxRequest(HttpMethod.GET, path)
                .flatMap(HttpClientRequest::rxSend)
                .flatMapPublisher(response -> {
                    assertThat(response.statusCode()).isEqualTo(200);
                    return response.toFlowable();
                })
                .test();

            obs
                .awaitDone(5, TimeUnit.SECONDS)
                .assertComplete()
                .assertValue(response -> {
                    assertThat(response).hasToString("new content: response from callout");
                    return true;
                })
                .assertNoErrors();

            wiremock.verify(getRequestedFor(urlPathEqualTo("/endpoint")));
            calloutServer.verify(getRequestedFor(urlPathEqualTo("/callout")));
        }

        @ParameterizedTest
        @DeployApi(
            { "/apis/v4/proxy/request-callout-http-fire-and-forget.json", "/apis/v4/proxy/response-callout-http-fire-and-forget.json" }
        )
        @ValueSource(strings = { "/proxy-request-fire-and-forget", "/proxy-response-fire-and-forget" })
        void should_do_callout_with_fire_and_forget_and_do_nothing_with_callout_response(String apiEndpoint, HttpClient client) {
            wiremock.stubFor(get("/endpoint").willReturn(ok("response from backend")));
            calloutServer.stubFor(get("/callout").willReturn(ok("response from callout")));

            var obs = client
                .rxRequest(HttpMethod.GET, apiEndpoint)
                .flatMap(HttpClientRequest::rxSend)
                .flatMapPublisher(response -> {
                    assertThat(response.statusCode()).isEqualTo(200);
                    return response.toFlowable();
                })
                .test();

            obs
                .awaitDone(5, TimeUnit.SECONDS)
                .assertComplete()
                .assertValue(response -> {
                    assertThat(response).hasToString("new content");
                    return true;
                })
                .assertNoErrors();

            wiremock.verify(getRequestedFor(urlPathEqualTo("/endpoint")));

            await()
                .atMost(10, TimeUnit.SECONDS)
                .untilAsserted(() ->
                    calloutServer.verify(getRequestedFor(urlPathEqualTo("/callout")).withHeader("X-Callout", equalTo("calloutHeader")))
                );
        }

        @ParameterizedTest
        @DeployApi({ "/apis/v4/proxy/request-callout-http-el-support.json", "/apis/v4/proxy/response-callout-http-el-support.json" })
        @ValueSource(strings = { "/proxy-request-el-support", "/proxy-response-el-support" })
        void should_process_el_before_callout(String apiEndpoint, HttpClient client) {
            String path = "/a-path";
            String body = new JsonObject().put("clé", "value").put("key", "välæur").encode();
            String header1 = "value1";
            String header2 = "value2";

            wiremock.stubFor(get("/endpoint").willReturn(ok("response from backend")));
            calloutServer.stubFor(post(path).willReturn(ok("response from callout")));

            var obs = client
                .rxRequest(HttpMethod.GET, apiEndpoint)
                .map(req ->
                    req.putHeader("X-Url", path).putHeader("X-Body", body).putHeader("X-Header1", header1).putHeader("X-Header2", header2)
                )
                .flatMap(HttpClientRequest::rxSend)
                .flatMapPublisher(response -> {
                    assertThat(response.statusCode()).isEqualTo(200);
                    return response.toFlowable();
                })
                .test();

            obs
                .awaitDone(5, TimeUnit.SECONDS)
                .assertComplete()
                .assertValue(response -> {
                    assertThat(response).hasToString("new content");
                    return true;
                })
                .assertNoErrors();

            wiremock.verify(getRequestedFor(urlPathEqualTo("/endpoint")));
            calloutServer.verify(
                postRequestedFor(urlPathEqualTo(path))
                    .withHeader("X-Callout1", equalTo(header1))
                    .withHeader("X-Callout2", equalTo(header2))
                    .withRequestBody(equalTo(body))
            );
        }

        @ParameterizedTest
        @DeployApi({ "/apis/v4/proxy/request-callout-http-exit-on-error.json", "/apis/v4/proxy/response-callout-http-exit-on-error.json" })
        @ValueSource(strings = { "/proxy-request-exit-on-error", "/proxy-response-exit-on-error" })
        void should_do_callout_and_interrupt_when_callout_fails(String apiEndpoint, HttpClient client) {
            wiremock.stubFor(get("/endpoint").willReturn(ok("response from backend")));
            calloutServer.stubFor(get("/callout").willReturn(aResponse().withStatus(502).withBody("callout failed")));

            var obs = client
                .rxRequest(HttpMethod.GET, apiEndpoint)
                .flatMap(HttpClientRequest::rxSend)
                .flatMapPublisher(response -> {
                    assertThat(response.statusCode()).isEqualTo(501);
                    return response.toFlowable();
                })
                .test();

            obs
                .awaitDone(5, TimeUnit.SECONDS)
                .assertComplete()
                .assertValue(response -> {
                    assertThat(response).hasToString("Error content with status 502");
                    return true;
                })
                .assertNoErrors();

            if (apiEndpoint.contains("request")) {
                wiremock.verify(0, getRequestedFor(urlPathEqualTo("/endpoint")));
            }
        }
    }

    @Nested
    class Message extends AbstractV4EngineTest {

        @Test
        @DeployApi("/apis/v4/message/sse-request-callout-http.json")
        void should_get_messages_with_default_configuration(HttpClient httpClient) {
            calloutServer.stubFor(get("/callout").willReturn(ok("response from callout")));

            startSseStream(httpClient)
                .awaitCount(1)
                .assertValue(content -> {
                    assertThat(content).contains("event: message").contains("data: ");
                    assertThat(content.split("data: ")[1].trim()).isEqualTo("new content: response from callout");
                    return true;
                });
        }

        static TestSubscriber<String> startSseStream(HttpClient httpClient) {
            return httpClient
                .rxRequest(HttpMethod.GET, "/sse-request-callout-http")
                .flatMap(request -> {
                    request.putHeader(HttpHeaderNames.ACCEPT.toString(), MediaType.TEXT_EVENT_STREAM);
                    return request.rxSend();
                })
                .flatMapPublisher(response -> {
                    assertThat(response.statusCode()).isEqualTo(200);
                    return response.toFlowable();
                })
                .map(Buffer::toString)
                .filter(content -> !content.startsWith("retry")) // ignore retry
                .filter(content -> !content.equals(":\n\n")) // ignore heartbeat
                .test();
        }
    }
}
