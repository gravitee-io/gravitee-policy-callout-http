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
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static io.gravitee.policy.v3.callout.CalloutHttpPolicyV3.CALLOUT_EXIT_ON_ERROR;
import static io.gravitee.policy.v3.callout.CalloutHttpPolicyV3.CALLOUT_HTTP_ERROR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;
import static test.RequestBuilder.aRequest;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.gravitee.common.http.HttpMethod;
import io.gravitee.el.TemplateEngine;
import io.gravitee.gateway.reactive.api.ExecutionFailure;
import io.gravitee.gateway.reactive.api.context.kafka.KafkaMessageExecutionContext;
import io.gravitee.gateway.reactive.api.message.kafka.KafkaMessage;
import io.gravitee.gateway.reactive.api.tracing.Tracer;
import io.gravitee.gateway.reactive.core.context.interruption.InterruptionFailureException;
import io.gravitee.node.api.configuration.Configuration;
import io.gravitee.policy.callout.configuration.CalloutHttpPolicyConfiguration;
import io.gravitee.policy.callout.configuration.Variable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.processors.ReplayProcessor;
import io.vertx.rxjava3.core.Vertx;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import test.ExecutionContextBuilder;
import test.stub.KafkaMessageRequestStub;
import test.stub.KafkaMessageResponseStub;
import test.stub.KafkaMessageStub;

class CalloutHttpPolicyV4Test {

    @RegisterExtension
    static WireMockExtension wiremock = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort().dynamicHttpsPort()).build();

    private Configuration nodeConfiguration;

    @BeforeEach
    void setUp() {
        nodeConfiguration = mock(Configuration.class);
    }

    @Nested
    class OnRequest {

        @Test
        void should_add_headers_to_callout_call() {
            wiremock.stubFor(get(urlEqualTo("/")).willReturn(aResponse().withStatus(200).withBody("{\"key\": \"a-value\"}")));

            var ctx = new ExecutionContextBuilder()
                .withComponent(Vertx.class, Vertx.vertx())
                .request(aRequest().header("X-Header2", "value2").build())
                .build();

            policy(
                CalloutHttpPolicyConfiguration.builder()
                    .url(targetUrl(false))
                    .method(HttpMethod.GET)
                    .headers(
                        List.of(
                            new io.gravitee.policy.callout.configuration.HttpHeader("header1", "value1"),
                            new io.gravitee.policy.callout.configuration.HttpHeader("header2", "{#request.headers['X-Header2'][0]}")
                        )
                    )
                    .build()
            )
                .onRequest(ctx)
                .test()
                .awaitDone(30, TimeUnit.SECONDS)
                .assertComplete();

            wiremock.verify(
                getRequestedFor(urlPathEqualTo("/")).withHeader("header1", equalTo("value1")).withHeader("header2", equalTo("value2"))
            );
        }

        @Test
        void should_not_add_null_header_to_callout_call() {
            wiremock.stubFor(get(urlEqualTo("/")).willReturn(aResponse().withStatus(200).withBody("{\"key\": \"a-value\"}")));

            var ctx = new ExecutionContextBuilder().withComponent(Vertx.class, Vertx.vertx()).request(aRequest().build()).build();

            policy(
                CalloutHttpPolicyConfiguration.builder()
                    .url(targetUrl(false))
                    .method(HttpMethod.GET)
                    .headers(
                        List.of(
                            new io.gravitee.policy.callout.configuration.HttpHeader("header1", "value1"),
                            new io.gravitee.policy.callout.configuration.HttpHeader("header2", "value2"),
                            new io.gravitee.policy.callout.configuration.HttpHeader("header3", null),
                            new io.gravitee.policy.callout.configuration.HttpHeader("header4", "{#context.attributes['unknown']}")
                        )
                    )
                    .build()
            )
                .onRequest(ctx)
                .test()
                .awaitDone(30, TimeUnit.SECONDS)
                .assertComplete();

            wiremock.verify(
                getRequestedFor(urlPathEqualTo("/")).withHeader("header1", equalTo("value1")).withHeader("header2", equalTo("value2"))
            );
        }

        @Test
        void should_send_static_body_to_callout_call() {
            wiremock.stubFor(get(urlEqualTo("/")).willReturn(aResponse().withStatus(200).withBody("{\"key\": \"a-value\"}")));

            var ctx = new ExecutionContextBuilder().withComponent(Vertx.class, Vertx.vertx()).request(aRequest().build()).build();

            String body = "static body";
            policy(CalloutHttpPolicyConfiguration.builder().url(targetUrl(false)).method(HttpMethod.GET).body(body).build())
                .onRequest(ctx)
                .test()
                .awaitDone(30, TimeUnit.SECONDS)
                .assertComplete();

            wiremock.verify(
                getRequestedFor(urlPathEqualTo("/"))
                    .withRequestBody(equalTo(body))
                    .withoutHeader("Transfer-Encoding")
                    .withHeader("Content-Length", equalTo(String.valueOf(body.length())))
            );
        }

        @Test
        void should_send_dynamic_body_to_callout_call() {
            wiremock.stubFor(get(urlEqualTo("/")).willReturn(aResponse().withStatus(200).withBody("{\"key\": \"a-value\"}")));

            String body = "dynamic body";
            var ctx = new ExecutionContextBuilder()
                .withComponent(Vertx.class, Vertx.vertx())
                .request(aRequest().header("X-Body", body).build())
                .build();

            policy(
                CalloutHttpPolicyConfiguration.builder()
                    .url(targetUrl(false))
                    .method(HttpMethod.GET)
                    .body("{#request.headers['X-Body'][0]}")
                    .build()
            )
                .onRequest(ctx)
                .test()
                .awaitDone(30, TimeUnit.SECONDS)
                .assertComplete();

            wiremock.verify(
                getRequestedFor(urlPathEqualTo("/"))
                    .withRequestBody(equalTo(body))
                    .withoutHeader("Transfer-Encoding")
                    .withHeader("Content-Length", equalTo(String.valueOf(body.length())))
            );
        }

        @ParameterizedTest
        @ValueSource(booleans = { true, false })
        void should_add_variables_in_context_when_callout_succeed(boolean exitOnError) {
            wiremock.stubFor(get(urlEqualTo("/")).willReturn(aResponse().withStatus(200).withBody("{\"key\": \"a-value\"}")));

            var ctx = new ExecutionContextBuilder().withComponent(Vertx.class, Vertx.vertx()).request(aRequest().build()).build();

            policy(
                CalloutHttpPolicyConfiguration.builder()
                    .url(targetUrl(false))
                    .method(HttpMethod.GET)
                    .exitOnError(exitOnError)
                    .errorCondition("{#calloutResponse.status != 200}")
                    .variables(
                        List.of(
                            new Variable("callout1", "static"),
                            new Variable("callout2", "{#jsonPath(#calloutResponse.content, '$.key')}")
                        )
                    )
                    .build()
            )
                .onRequest(ctx)
                .test()
                .awaitDone(30, TimeUnit.SECONDS)
                .assertComplete();

            assertThat(ctx.getAttributes()).containsEntry("callout1", "static").containsEntry("callout2", "a-value");
        }

        @ParameterizedTest
        @ValueSource(booleans = { true, false })
        void should_call_and_do_nothing_when_no_variables_defined(boolean exitOnError) {
            wiremock.stubFor(get(urlEqualTo("/")).willReturn(aResponse().withStatus(200).withBody("{\"key\": \"a-value\"}")));

            var ctx = new ExecutionContextBuilder().withComponent(Vertx.class, Vertx.vertx()).request(aRequest().build()).build();

            policy(
                CalloutHttpPolicyConfiguration.builder()
                    .url(targetUrl(false))
                    .method(HttpMethod.GET)
                    .exitOnError(exitOnError)
                    .errorCondition("{#calloutResponse.status != 200}")
                    .build()
            )
                .onRequest(ctx)
                .test()
                .awaitDone(30, TimeUnit.SECONDS)
                .assertComplete();

            assertThat(ctx.getAttributes()).isEmpty();
            wiremock.verify(getRequestedFor(urlPathEqualTo("/")));
        }

        @Test
        void should_interrupt_when_exitOnError_and_use_default_message_when_no_error_content_defined() {
            wiremock.stubFor(get(urlEqualTo("/")).willReturn(aResponse().withStatus(400).withBody("Bad request")));

            var ctx = new ExecutionContextBuilder().withComponent(Vertx.class, Vertx.vertx()).request(aRequest().build()).build();

            policy(
                CalloutHttpPolicyConfiguration.builder()
                    .url(targetUrl(false))
                    .method(HttpMethod.GET)
                    .exitOnError(true)
                    .errorCondition("{#calloutResponse.status != 200}")
                    .build()
            )
                .onRequest(ctx)
                .test()
                .awaitDone(30, TimeUnit.SECONDS)
                .assertError(e -> {
                    assertThat(e)
                        .isInstanceOf(InterruptionFailureException.class)
                        .extracting(error -> ((InterruptionFailureException) error).getExecutionFailure())
                        .extracting(ExecutionFailure::statusCode, ExecutionFailure::key, ExecutionFailure::message)
                        .containsExactly(500, CALLOUT_EXIT_ON_ERROR, "Request is terminated.");
                    return true;
                });
        }

        @Test
        void should_interrupt_when_exitOnError_and_use_provided_configuration() {
            wiremock.stubFor(get(urlEqualTo("/")).willReturn(aResponse().withStatus(400).withBody("Bad request")));

            var ctx = new ExecutionContextBuilder().withComponent(Vertx.class, Vertx.vertx()).request(aRequest().build()).build();

            policy(
                CalloutHttpPolicyConfiguration.builder()
                    .url(targetUrl(false))
                    .method(HttpMethod.GET)
                    .exitOnError(true)
                    .errorCondition("{#calloutResponse.status != 200}")
                    .errorStatusCode(502)
                    .errorContent("Error: {#calloutResponse.content}")
                    .build()
            )
                .onRequest(ctx)
                .test()
                .awaitDone(30, TimeUnit.SECONDS)
                .assertError(e -> {
                    assertThat(e)
                        .isInstanceOf(InterruptionFailureException.class)
                        .extracting(error -> ((InterruptionFailureException) error).getExecutionFailure())
                        .extracting(ExecutionFailure::statusCode, ExecutionFailure::key, ExecutionFailure::message)
                        .containsExactly(502, CALLOUT_EXIT_ON_ERROR, "Error: Bad request");
                    return true;
                });
        }

        @Test
        void should_interrupt_when_fail_to_call_target_callout() {
            var ctx = new ExecutionContextBuilder().withComponent(Vertx.class, Vertx.vertx()).request(aRequest().build()).build();

            policy(
                CalloutHttpPolicyConfiguration.builder()
                    .url("http://unknown")
                    .method(HttpMethod.GET)
                    .exitOnError(true)
                    .errorCondition("{#calloutResponse.status != 200}")
                    .build()
            )
                .onRequest(ctx)
                .test()
                .awaitDone(30, TimeUnit.SECONDS)
                .assertError(e -> {
                    assertThat(e).isInstanceOf(InterruptionFailureException.class);
                    var executionFailure = ((InterruptionFailureException) e).getExecutionFailure();
                    assertThat(executionFailure.statusCode()).isEqualTo(500);
                    assertThat(executionFailure.key()).isEqualTo(CALLOUT_HTTP_ERROR);
                    assertThat(executionFailure.message()).contains("Failed to resolve 'unknown'");
                    return true;
                });
        }

        @ParameterizedTest
        @ValueSource(booleans = { true, false })
        void should_call_and_do_nothing_when_fire_and_forget_defined(boolean exitOnError) {
            wiremock.stubFor(get(urlEqualTo("/")).willReturn(aResponse().withStatus(200).withBody("{\"key\": \"a-value\"}")));

            var ctx = new ExecutionContextBuilder().withComponent(Vertx.class, Vertx.vertx()).request(aRequest().build()).build();

            policy(
                CalloutHttpPolicyConfiguration.builder()
                    .url(targetUrl(false))
                    .method(HttpMethod.GET)
                    .fireAndForget(true)
                    .variables(
                        List.of(
                            new Variable("callout1", "static"),
                            new Variable("callout2", "{#jsonPath(#calloutResponse.content, '$.key')}")
                        )
                    )
                    .exitOnError(exitOnError)
                    .errorCondition("{#calloutResponse.status != 200}")
                    .build()
            )
                .onRequest(ctx)
                .test()
                .awaitDone(30, TimeUnit.SECONDS)
                .assertComplete();

            assertThat(ctx.getAttributes()).isEmpty();
            await()
                .atMost(10, TimeUnit.SECONDS)
                .untilAsserted(() -> wiremock.verify(getRequestedFor(urlPathEqualTo("/"))));
        }

        @Test
        void should_call_https_target() {
            wiremock.stubFor(get(urlEqualTo("/")).willReturn(aResponse().withStatus(200).withBody("{\"key\": \"a-value\"}")));

            var ctx = new ExecutionContextBuilder()
                .withComponent(Vertx.class, Vertx.vertx())
                .request(aRequest().header("X-Header2", "value2").build())
                .build();

            policy(
                CalloutHttpPolicyConfiguration.builder()
                    .url(targetUrl(true))
                    .method(HttpMethod.GET)
                    .headers(
                        List.of(
                            new io.gravitee.policy.callout.configuration.HttpHeader("header1", "value1"),
                            new io.gravitee.policy.callout.configuration.HttpHeader("header2", "{#request.headers['X-Header2'][0]}")
                        )
                    )
                    .build()
            )
                .onRequest(ctx)
                .test()
                .awaitDone(30, TimeUnit.SECONDS)
                .assertComplete();

            wiremock.verify(
                getRequestedFor(urlPathEqualTo("/")).withHeader("header1", equalTo("value1")).withHeader("header2", equalTo("value2"))
            );
        }

        @Test
        void should_call_target_using_system_proxy() {
            wiremock.stubFor(get(urlEqualTo("/")).willReturn(aResponse().withStatus(200).withBody("{\"key\": \"a-value\"}")));

            var ctx = new ExecutionContextBuilder()
                .withComponent(Vertx.class, Vertx.vertx())
                .withComponent(io.gravitee.node.api.configuration.Configuration.class, nodeConfiguration)
                .request(aRequest().header("X-Header2", "value2").build())
                .build();

            policy(CalloutHttpPolicyConfiguration.builder().useSystemProxy(true).url(targetUrl(false)).method(HttpMethod.GET).build())
                .onRequest(ctx)
                .test()
                .awaitDone(30, TimeUnit.SECONDS)
                .assertComplete();

            wiremock.verify(getRequestedFor(urlPathEqualTo("/")));

            verify(nodeConfiguration).getProperty("system.proxy.port");
            verify(nodeConfiguration).getProperty("system.proxy.type");
            verify(nodeConfiguration).getProperty("system.proxy.host");
            verify(nodeConfiguration).getProperty("system.proxy.username");
            verify(nodeConfiguration).getProperty("system.proxy.password");
        }
    }

    @Nested
    class OnResponse {

        @Test
        void should_add_headers_to_callout_call() {
            wiremock.stubFor(get(urlEqualTo("/")).willReturn(aResponse().withStatus(200).withBody("{\"key\": \"a-value\"}")));

            var ctx = new ExecutionContextBuilder()
                .withComponent(Vertx.class, Vertx.vertx())
                .request(aRequest().header("X-Header2", "value2").build())
                .build();

            policy(
                CalloutHttpPolicyConfiguration.builder()
                    .url(targetUrl(false))
                    .method(HttpMethod.GET)
                    .headers(
                        List.of(
                            new io.gravitee.policy.callout.configuration.HttpHeader("header1", "value1"),
                            new io.gravitee.policy.callout.configuration.HttpHeader("header2", "{#request.headers['X-Header2'][0]}")
                        )
                    )
                    .build()
            )
                .onResponse(ctx)
                .test()
                .awaitDone(30, TimeUnit.SECONDS)
                .assertComplete();

            wiremock.verify(
                getRequestedFor(urlPathEqualTo("/")).withHeader("header1", equalTo("value1")).withHeader("header2", equalTo("value2"))
            );
        }

        @Test
        void should_not_add_null_header_to_callout_call() {
            wiremock.stubFor(get(urlEqualTo("/")).willReturn(aResponse().withStatus(200).withBody("{\"key\": \"a-value\"}")));

            var ctx = new ExecutionContextBuilder().withComponent(Vertx.class, Vertx.vertx()).request(aRequest().build()).build();

            policy(
                CalloutHttpPolicyConfiguration.builder()
                    .url(targetUrl(false))
                    .method(HttpMethod.GET)
                    .headers(
                        List.of(
                            new io.gravitee.policy.callout.configuration.HttpHeader("header1", "value1"),
                            new io.gravitee.policy.callout.configuration.HttpHeader("header2", "value2"),
                            new io.gravitee.policy.callout.configuration.HttpHeader("header3", null),
                            new io.gravitee.policy.callout.configuration.HttpHeader("header4", "{#context.attributes['unknown']}")
                        )
                    )
                    .build()
            )
                .onResponse(ctx)
                .test()
                .awaitDone(30, TimeUnit.SECONDS)
                .assertComplete();

            wiremock.verify(
                getRequestedFor(urlPathEqualTo("/")).withHeader("header1", equalTo("value1")).withHeader("header2", equalTo("value2"))
            );
        }

        @Test
        void should_send_static_body_to_callout_call() {
            wiremock.stubFor(get(urlEqualTo("/")).willReturn(aResponse().withStatus(200).withBody("{\"key\": \"a-value\"}")));

            var ctx = new ExecutionContextBuilder().withComponent(Vertx.class, Vertx.vertx()).request(aRequest().build()).build();

            String body = "static body";
            policy(CalloutHttpPolicyConfiguration.builder().url(targetUrl(false)).method(HttpMethod.GET).body(body).build())
                .onResponse(ctx)
                .test()
                .awaitDone(30, TimeUnit.SECONDS)
                .assertComplete();

            wiremock.verify(
                getRequestedFor(urlPathEqualTo("/"))
                    .withRequestBody(equalTo(body))
                    .withoutHeader("Transfer-Encoding")
                    .withHeader("Content-Length", equalTo(String.valueOf(body.length())))
            );
        }

        @Test
        void should_send_dynamic_body_to_callout_call() {
            wiremock.stubFor(get(urlEqualTo("/")).willReturn(aResponse().withStatus(200).withBody("{\"key\": \"a-value\"}")));

            String body = "dynamic body";
            var ctx = new ExecutionContextBuilder()
                .withComponent(Vertx.class, Vertx.vertx())
                .request(aRequest().header("X-Body", body).build())
                .build();

            policy(
                CalloutHttpPolicyConfiguration.builder()
                    .url(targetUrl(false))
                    .method(HttpMethod.GET)
                    .body("{#request.headers['X-Body'][0]}")
                    .build()
            )
                .onResponse(ctx)
                .test()
                .awaitDone(30, TimeUnit.SECONDS)
                .assertComplete();

            wiremock.verify(
                getRequestedFor(urlPathEqualTo("/"))
                    .withRequestBody(equalTo(body))
                    .withoutHeader("Transfer-Encoding")
                    .withHeader("Content-Length", equalTo(String.valueOf(body.length())))
            );
        }

        @ParameterizedTest
        @ValueSource(booleans = { true, false })
        void should_add_variables_in_context_when_callout_succeed(boolean exitOnError) {
            wiremock.stubFor(get(urlEqualTo("/")).willReturn(aResponse().withStatus(200).withBody("{\"key\": \"a-value\"}")));

            var ctx = new ExecutionContextBuilder().withComponent(Vertx.class, Vertx.vertx()).request(aRequest().build()).build();

            policy(
                CalloutHttpPolicyConfiguration.builder()
                    .url(targetUrl(false))
                    .method(HttpMethod.GET)
                    .exitOnError(exitOnError)
                    .errorCondition("{#calloutResponse.status != 200}")
                    .variables(
                        List.of(
                            new Variable("callout1", "static"),
                            new Variable("callout2", "{#jsonPath(#calloutResponse.content, '$.key')}")
                        )
                    )
                    .build()
            )
                .onResponse(ctx)
                .test()
                .awaitDone(30, TimeUnit.SECONDS)
                .assertComplete();

            assertThat(ctx.getAttributes()).containsEntry("callout1", "static").containsEntry("callout2", "a-value");
        }

        @ParameterizedTest
        @ValueSource(booleans = { true, false })
        void should_call_and_do_nothing_when_no_variables_defined(boolean exitOnError) {
            wiremock.stubFor(get(urlEqualTo("/")).willReturn(aResponse().withStatus(200).withBody("{\"key\": \"a-value\"}")));

            var ctx = new ExecutionContextBuilder().withComponent(Vertx.class, Vertx.vertx()).request(aRequest().build()).build();

            policy(
                CalloutHttpPolicyConfiguration.builder()
                    .url(targetUrl(false))
                    .method(HttpMethod.GET)
                    .exitOnError(exitOnError)
                    .errorCondition("{#calloutResponse.status != 200}")
                    .build()
            )
                .onResponse(ctx)
                .test()
                .awaitDone(30, TimeUnit.SECONDS)
                .assertComplete();

            assertThat(ctx.getAttributes()).isEmpty();
            wiremock.verify(getRequestedFor(urlPathEqualTo("/")));
        }

        @Test
        void should_interrupt_when_exitOnError_and_use_default_message_when_no_error_content_defined() {
            wiremock.stubFor(get(urlEqualTo("/")).willReturn(aResponse().withStatus(400).withBody("Bad request")));

            var ctx = new ExecutionContextBuilder().withComponent(Vertx.class, Vertx.vertx()).request(aRequest().build()).build();

            policy(
                CalloutHttpPolicyConfiguration.builder()
                    .url(targetUrl(false))
                    .method(HttpMethod.GET)
                    .exitOnError(true)
                    .errorCondition("{#calloutResponse.status != 200}")
                    .build()
            )
                .onResponse(ctx)
                .test()
                .awaitDone(30, TimeUnit.SECONDS)
                .assertError(e -> {
                    assertThat(e)
                        .isInstanceOf(InterruptionFailureException.class)
                        .extracting(error -> ((InterruptionFailureException) error).getExecutionFailure())
                        .extracting(ExecutionFailure::statusCode, ExecutionFailure::key, ExecutionFailure::message)
                        .containsExactly(500, CALLOUT_EXIT_ON_ERROR, "Request is terminated.");
                    return true;
                });
        }

        @Test
        void should_interrupt_when_exitOnError_and_use_provided_configuration() {
            wiremock.stubFor(get(urlEqualTo("/")).willReturn(aResponse().withStatus(400).withBody("Bad request")));

            var ctx = new ExecutionContextBuilder().withComponent(Vertx.class, Vertx.vertx()).request(aRequest().build()).build();

            policy(
                CalloutHttpPolicyConfiguration.builder()
                    .url(targetUrl(false))
                    .method(HttpMethod.GET)
                    .exitOnError(true)
                    .errorCondition("{#calloutResponse.status != 200}")
                    .errorStatusCode(502)
                    .errorContent("Error: {#calloutResponse.content}")
                    .build()
            )
                .onResponse(ctx)
                .test()
                .awaitDone(30, TimeUnit.SECONDS)
                .assertError(e -> {
                    assertThat(e)
                        .isInstanceOf(InterruptionFailureException.class)
                        .extracting(error -> ((InterruptionFailureException) error).getExecutionFailure())
                        .extracting(ExecutionFailure::statusCode, ExecutionFailure::key, ExecutionFailure::message)
                        .containsExactly(502, CALLOUT_EXIT_ON_ERROR, "Error: Bad request");
                    return true;
                });
        }

        @ParameterizedTest
        @ValueSource(booleans = { true, false })
        void should_call_and_do_nothing_when_fire_and_forget_defined(boolean exitOnError) {
            wiremock.stubFor(get(urlEqualTo("/")).willReturn(aResponse().withStatus(200).withBody("{\"key\": \"a-value\"}")));

            var ctx = new ExecutionContextBuilder().withComponent(Vertx.class, Vertx.vertx()).request(aRequest().build()).build();

            policy(
                CalloutHttpPolicyConfiguration.builder()
                    .url(targetUrl(false))
                    .method(HttpMethod.GET)
                    .fireAndForget(true)
                    .variables(
                        List.of(
                            new Variable("callout1", "static"),
                            new Variable("callout2", "{#jsonPath(#calloutResponse.content, '$.key')}")
                        )
                    )
                    .exitOnError(exitOnError)
                    .errorCondition("{#calloutResponse.status != 200}")
                    .build()
            )
                .onResponse(ctx)
                .test()
                .awaitDone(30, TimeUnit.SECONDS)
                .assertComplete();

            assertThat(ctx.getAttributes()).isEmpty();
            await()
                .atMost(10, TimeUnit.SECONDS)
                .untilAsserted(() -> wiremock.verify(getRequestedFor(urlPathEqualTo("/"))));
        }

        @Test
        void should_call_https_target() {
            wiremock.stubFor(get(urlEqualTo("/")).willReturn(aResponse().withStatus(200).withBody("{\"key\": \"a-value\"}")));

            var ctx = new ExecutionContextBuilder()
                .withComponent(Vertx.class, Vertx.vertx())
                .request(aRequest().header("X-Header2", "value2").build())
                .build();

            policy(
                CalloutHttpPolicyConfiguration.builder()
                    .url(targetUrl(true))
                    .method(HttpMethod.GET)
                    .headers(
                        List.of(
                            new io.gravitee.policy.callout.configuration.HttpHeader("header1", "value1"),
                            new io.gravitee.policy.callout.configuration.HttpHeader("header2", "{#request.headers['X-Header2'][0]}")
                        )
                    )
                    .build()
            )
                .onResponse(ctx)
                .test()
                .awaitDone(30, TimeUnit.SECONDS)
                .assertComplete();

            wiremock.verify(
                getRequestedFor(urlPathEqualTo("/")).withHeader("header1", equalTo("value1")).withHeader("header2", equalTo("value2"))
            );
        }

        @Test
        void should_call_target_using_system_proxy() {
            wiremock.stubFor(get(urlEqualTo("/")).willReturn(aResponse().withStatus(200).withBody("{\"key\": \"a-value\"}")));

            var ctx = new ExecutionContextBuilder()
                .withComponent(Vertx.class, Vertx.vertx())
                .withComponent(io.gravitee.node.api.configuration.Configuration.class, nodeConfiguration)
                .request(aRequest().header("X-Header2", "value2").build())
                .build();

            policy(CalloutHttpPolicyConfiguration.builder().useSystemProxy(true).url(targetUrl(false)).method(HttpMethod.GET).build())
                .onResponse(ctx)
                .test()
                .awaitDone(30, TimeUnit.SECONDS)
                .assertComplete();

            wiremock.verify(getRequestedFor(urlPathEqualTo("/")));

            verify(nodeConfiguration).getProperty("system.proxy.port");
            verify(nodeConfiguration).getProperty("system.proxy.type");
            verify(nodeConfiguration).getProperty("system.proxy.host");
            verify(nodeConfiguration).getProperty("system.proxy.username");
            verify(nodeConfiguration).getProperty("system.proxy.password");
        }
    }

    @Nested
    class OnMessageRequest {

        @ParameterizedTest
        @ValueSource(ints = { 1, 5, 10 })
        void should_make_http_calls_for_kafka_messages(int recordsCount) {
            wiremock.stubFor(get(urlEqualTo("/")).willReturn(aResponse().withStatus(200).withBody("{\"key\": \"a-value\"}")));

            KafkaMessageExecutionContext ctx = mock(KafkaMessageExecutionContext.class);
            final KafkaMessageRequestStub request = new KafkaMessageRequestStub();
            when(ctx.request()).thenReturn(request);
            when(ctx.getTemplateEngine(any())).thenReturn(TemplateEngine.templateEngine());
            when(ctx.getTemplateEngine()).thenReturn(TemplateEngine.templateEngine());
            when(ctx.getComponent(Vertx.class)).thenReturn(Vertx.vertx());
            when(ctx.getTracer()).thenReturn(mock(Tracer.class));

            List<KafkaMessage> messages = new ArrayList<>();
            for (int i = 0; i < recordsCount; i++) {
                KafkaMessage stubMessage = new KafkaMessageStub("test_" + i);
                messages.add(stubMessage);
            }

            policy(CalloutHttpPolicyConfiguration.builder().url(targetUrl(false)).method(HttpMethod.GET).build())
                .onMessageRequest(ctx)
                .doOnComplete(() -> request.messages(Flowable.fromIterable(messages)))
                .test()
                .awaitDone(3, TimeUnit.SECONDS)
                .assertComplete();

            ReplayProcessor<KafkaMessage> messagesEmittedToBrokerProcessor = ReplayProcessor.create();
            request.messages().doOnNext(messagesEmittedToBrokerProcessor::onNext).test().awaitDone(3, TimeUnit.SECONDS).assertComplete();

            await()
                .atMost(10, TimeUnit.SECONDS)
                .untilAsserted(() -> wiremock.verify(recordsCount, getRequestedFor(urlPathEqualTo("/"))));
        }
    }

    @Nested
    class OnMessageResponse {

        @ParameterizedTest
        @ValueSource(ints = { 1, 5, 10 })
        void should_make_http_calls_for_kafka_messages(int recordsCount) {
            wiremock.stubFor(get(urlEqualTo("/")).willReturn(aResponse().withStatus(200).withBody("{\"key\": \"a-value\"}")));

            KafkaMessageExecutionContext ctx = mock(KafkaMessageExecutionContext.class);
            final KafkaMessageResponseStub response = new KafkaMessageResponseStub();
            when(ctx.response()).thenReturn(response);
            when(ctx.getTemplateEngine(any())).thenReturn(TemplateEngine.templateEngine());
            when(ctx.getTemplateEngine()).thenReturn(TemplateEngine.templateEngine());
            when(ctx.getComponent(Vertx.class)).thenReturn(Vertx.vertx());
            when(ctx.getTracer()).thenReturn(mock(Tracer.class));

            List<KafkaMessage> messages = new ArrayList<>();
            for (int i = 0; i < recordsCount; i++) {
                KafkaMessage stubMessage = new KafkaMessageStub("test_" + i);
                messages.add(stubMessage);
            }

            policy(CalloutHttpPolicyConfiguration.builder().url(targetUrl(false)).method(HttpMethod.GET).build())
                .onMessageResponse(ctx)
                .doOnComplete(() -> response.messages(Flowable.fromIterable(messages)))
                .test()
                .awaitDone(3, TimeUnit.SECONDS)
                .assertComplete();

            ReplayProcessor<KafkaMessage> messagesEmittedToBrokerProcessor = ReplayProcessor.create();
            response.messages().doOnNext(messagesEmittedToBrokerProcessor::onNext).test().awaitDone(3, TimeUnit.SECONDS).assertComplete();

            await()
                .atMost(10, TimeUnit.SECONDS)
                .untilAsserted(() -> wiremock.verify(recordsCount, getRequestedFor(urlPathEqualTo("/"))));
        }
    }

    String targetUrl(boolean https) {
        if (https) {
            return "https://localhost:" + wiremock.getHttpsPort() + "/";
        }
        return "http://localhost:" + wiremock.getPort() + "/";
    }

    CalloutHttpPolicy policy(CalloutHttpPolicyConfiguration configuration) {
        return new CalloutHttpPolicy(configuration);
    }
}
