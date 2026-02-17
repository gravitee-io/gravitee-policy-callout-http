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
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static test.RequestBuilder.aRequest;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.gravitee.common.http.HttpMethod;
import io.gravitee.el.TemplateEngine;
import io.gravitee.gateway.reactive.api.ExecutionFailure;
import io.gravitee.gateway.reactive.api.context.kafka.KafkaMessageExecutionContext;
import io.gravitee.gateway.reactive.api.context.kafka.KafkaMessageRequest;
import io.gravitee.gateway.reactive.api.context.kafka.KafkaMessageResponse;
import io.gravitee.gateway.reactive.api.message.kafka.KafkaMessage;
import io.gravitee.gateway.reactive.api.tracing.Tracer;
import io.gravitee.gateway.reactive.core.context.interruption.InterruptionFailureException;
import io.gravitee.node.api.configuration.Configuration;
import io.gravitee.policy.callout.configuration.CalloutHttpPolicyConfiguration;
import io.gravitee.policy.callout.configuration.Variable;
import io.gravitee.reporter.api.v4.metric.Metrics;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.vertx.rxjava3.core.Vertx;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import test.ExecutionContextBuilder;
import test.stub.KafkaMessageStub;

class CalloutHttpPolicyV4Test {

    @RegisterExtension
    static WireMockExtension wiremock = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort().dynamicHttpsPort()).build();

    private Configuration nodeConfiguration;

    @BeforeEach
    void setUp() {
        nodeConfiguration = mock(Configuration.class);
        when(nodeConfiguration.getProperty(anyString())).thenReturn("");
        when(nodeConfiguration.getProperty(anyString(), anyString())).thenReturn("");
        when(nodeConfiguration.getProperty(anyString(), any(Class.class))).thenReturn(null);
        when(nodeConfiguration.getProperty(anyString(), any(Class.class), any())).thenReturn(null);
    }

    private void setMetricsOnContext(Object ctx) {
        try {
            Field metricsField = ctx.getClass().getDeclaredField("metrics");
            metricsField.setAccessible(true);
            metricsField.set(ctx, mock(Metrics.class));
        } catch (Exception e) {
            try {
                Field metricsField = ctx.getClass().getSuperclass().getDeclaredField("metrics");
                metricsField.setAccessible(true);
                metricsField.set(ctx, mock(Metrics.class));
            } catch (Exception ex) {
                throw new RuntimeException("Could not set metrics on context", ex);
            }
        }
    }

    @Nested
    class OnRequest {

        @Test
        void should_add_headers_to_callout_call() {
            wiremock.stubFor(get(urlEqualTo("/")).willReturn(aResponse().withStatus(200).withBody("{\"key\": \"a-value\"}")));

            var ctx = new ExecutionContextBuilder()
                .withComponent(Vertx.class, Vertx.vertx())
                .withComponent(Configuration.class, nodeConfiguration)
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

            var ctx = new ExecutionContextBuilder()
                .withComponent(Vertx.class, Vertx.vertx())
                .withComponent(Configuration.class, nodeConfiguration)
                .request(aRequest().build())
                .build();

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

            var ctx = new ExecutionContextBuilder()
                .withComponent(Vertx.class, Vertx.vertx())
                .withComponent(Configuration.class, nodeConfiguration)
                .request(aRequest().build())
                .build();

            String body = "static body";
            policy(CalloutHttpPolicyConfiguration.builder().url(targetUrl(false)).method(HttpMethod.GET).body(body).build())
                .onRequest(ctx)
                .test()
                .awaitDone(30, TimeUnit.SECONDS)
                .assertComplete();

            wiremock.verify(getRequestedFor(urlPathEqualTo("/")).withRequestBody(equalTo(body)));
        }

        @Test
        void should_send_dynamic_body_to_callout_call() {
            wiremock.stubFor(get(urlEqualTo("/")).willReturn(aResponse().withStatus(200).withBody("{\"key\": \"a-value\"}")));

            String body = "dynamic body";
            var ctx = new ExecutionContextBuilder()
                .withComponent(Vertx.class, Vertx.vertx())
                .withComponent(Configuration.class, nodeConfiguration)
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

            wiremock.verify(getRequestedFor(urlPathEqualTo("/")).withRequestBody(equalTo(body)));
        }

        @ParameterizedTest
        @ValueSource(booleans = { true, false })
        void should_add_variables_in_context_when_callout_succeed(boolean exitOnError) {
            wiremock.stubFor(get(urlEqualTo("/")).willReturn(aResponse().withStatus(200).withBody("{\"key\": \"a-value\"}")));

            var ctx = new ExecutionContextBuilder()
                .withComponent(Vertx.class, Vertx.vertx())
                .withComponent(Configuration.class, nodeConfiguration)
                .request(aRequest().build())
                .build();

            policy(
                CalloutHttpPolicyConfiguration.builder()
                    .url(targetUrl(false))
                    .method(HttpMethod.GET)
                    .exitOnError(exitOnError)
                    .errorCondition("{#calloutResponse.status != 200}")
                    .variables(
                        List.of(
                            new Variable("callout1", "static"),
                            new Variable("callout2", "{#jsonPath(#calloutResponse.content, '$.key')}"),
                            new Variable("callout3", "{#jsonPath(#calloutResponse.content, '$')}", false)
                        )
                    )
                    .build()
            )
                .onRequest(ctx)
                .test()
                .awaitDone(30, TimeUnit.SECONDS)
                .assertComplete();

            assertThat(ctx.getAttributes())
                .containsEntry("callout1", "static")
                .containsEntry("callout2", "a-value")
                .containsEntry("callout3", Map.of("key", "a-value"))
                .containsKey("calloutResponse");
        }

        @ParameterizedTest
        @ValueSource(booleans = { true, false })
        void should_call_and_do_nothing_when_no_variables_defined(boolean exitOnError) {
            wiremock.stubFor(get(urlEqualTo("/")).willReturn(aResponse().withStatus(200).withBody("{\"key\": \"a-value\"}")));

            var ctx = new ExecutionContextBuilder()
                .withComponent(Vertx.class, Vertx.vertx())
                .withComponent(Configuration.class, nodeConfiguration)
                .request(aRequest().build())
                .build();

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

            assertThat(ctx.getAttributes()).containsOnlyKeys("calloutResponse");
            wiremock.verify(getRequestedFor(urlPathEqualTo("/")));
        }

        @Test
        void should_interrupt_when_exitOnError_and_use_default_message_when_no_error_content_defined() {
            wiremock.stubFor(get(urlEqualTo("/")).willReturn(aResponse().withStatus(400).withBody("Bad request")));

            var ctx = new ExecutionContextBuilder()
                .withComponent(Vertx.class, Vertx.vertx())
                .withComponent(Configuration.class, nodeConfiguration)
                .request(aRequest().build())
                .build();

            setMetricsOnContext(ctx);

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
                        .containsExactly(500, CalloutHttpPolicy.CALLOUT_EXIT_ON_ERROR, "Request is terminated.");
                    return true;
                });
        }

        @Test
        void should_interrupt_when_exitOnError_and_use_provided_configuration() {
            wiremock.stubFor(get(urlEqualTo("/")).willReturn(aResponse().withStatus(400).withBody("Bad request")));

            var ctx = new ExecutionContextBuilder()
                .withComponent(Vertx.class, Vertx.vertx())
                .withComponent(Configuration.class, nodeConfiguration)
                .request(aRequest().build())
                .build();

            setMetricsOnContext(ctx);

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
                        .containsExactly(502, CalloutHttpPolicy.CALLOUT_EXIT_ON_ERROR, "Error: Bad request");
                    return true;
                });
        }

        @Test
        void should_interrupt_when_fail_to_call_target_callout() {
            var ctx = new ExecutionContextBuilder()
                .withComponent(Vertx.class, Vertx.vertx())
                .withComponent(Configuration.class, nodeConfiguration)
                .request(aRequest().build())
                .build();

            setMetricsOnContext(ctx);

            policy(
                CalloutHttpPolicyConfiguration.builder()
                    .url("http://unknown:8080")
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
                    assertThat(executionFailure.key()).isEqualTo(CalloutHttpPolicy.CALLOUT_HTTP_ERROR);
                    assertThat(executionFailure.message()).contains("unknown");
                    return true;
                });
        }

        @ParameterizedTest
        @ValueSource(booleans = { true, false })
        void should_call_and_do_nothing_when_fire_and_forget_defined(boolean exitOnError) {
            wiremock.stubFor(get(urlEqualTo("/")).willReturn(aResponse().withStatus(200).withBody("{\"key\": \"a-value\"}")));

            var ctx = new ExecutionContextBuilder()
                .withComponent(Vertx.class, Vertx.vertx())
                .withComponent(Configuration.class, nodeConfiguration)
                .request(aRequest().build())
                .build();

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
                .withComponent(Configuration.class, nodeConfiguration)
                .request(aRequest().header("X-Header2", "value2").build())
                .build();

            // Configure SSL to trust all certificates and disable hostname verification for testing
            var configuration = CalloutHttpPolicyConfiguration.builder()
                .url(targetUrl(true))
                .method(HttpMethod.GET)
                .headers(
                    List.of(
                        new io.gravitee.policy.callout.configuration.HttpHeader("header1", "value1"),
                        new io.gravitee.policy.callout.configuration.HttpHeader("header2", "{#request.headers['X-Header2'][0]}")
                    )
                )
                .build();

            configuration.getSslOptions().setTrustAll(true);
            configuration.getSslOptions().setHostnameVerifier(false);

            policy(configuration).onRequest(ctx).test().awaitDone(30, TimeUnit.SECONDS).assertComplete();

            wiremock.verify(
                getRequestedFor(urlPathEqualTo("/")).withHeader("header1", equalTo("value1")).withHeader("header2", equalTo("value2"))
            );
        }

        @Test
        void should_call_target_using_system_proxy() {
            wiremock.stubFor(get(urlEqualTo("/")).willReturn(aResponse().withStatus(200).withBody("{\"key\": \"a-value\"}")));

            var ctx = new ExecutionContextBuilder()
                .withComponent(Vertx.class, Vertx.vertx())
                .withComponent(Configuration.class, nodeConfiguration)
                .request(aRequest().header("X-Header2", "value2").build())
                .build();

            var configuration = CalloutHttpPolicyConfiguration.builder()
                .useSystemProxy(true)
                .url(targetUrl(false))
                .method(HttpMethod.GET)
                .build();

            configuration.setUseSystemProxy(true);

            policy(configuration).onRequest(ctx).test().awaitDone(30, TimeUnit.SECONDS).assertComplete();

            wiremock.verify(getRequestedFor(urlPathEqualTo("/")));

            verify(nodeConfiguration, atLeastOnce()).getProperty("system.proxy.host");
            verify(nodeConfiguration, atLeastOnce()).getProperty("system.proxy.port");
            verify(nodeConfiguration, atLeastOnce()).getProperty("system.proxy.type");
            verify(nodeConfiguration, atLeastOnce()).getProperty("system.proxy.username");
            verify(nodeConfiguration, atLeastOnce()).getProperty("system.proxy.password");
        }
    }

    @Nested
    class OnResponse {

        @Test
        void should_add_headers_to_callout_call() {
            wiremock.stubFor(get(urlEqualTo("/")).willReturn(aResponse().withStatus(200).withBody("{\"key\": \"a-value\"}")));

            var ctx = new ExecutionContextBuilder()
                .withComponent(Vertx.class, Vertx.vertx())
                .withComponent(Configuration.class, nodeConfiguration)
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

            var ctx = new ExecutionContextBuilder()
                .withComponent(Vertx.class, Vertx.vertx())
                .withComponent(Configuration.class, nodeConfiguration)
                .request(aRequest().build())
                .build();

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

            var ctx = new ExecutionContextBuilder()
                .withComponent(Vertx.class, Vertx.vertx())
                .withComponent(Configuration.class, nodeConfiguration)
                .request(aRequest().build())
                .build();

            String body = "static body";
            policy(CalloutHttpPolicyConfiguration.builder().url(targetUrl(false)).method(HttpMethod.GET).body(body).build())
                .onResponse(ctx)
                .test()
                .awaitDone(30, TimeUnit.SECONDS)
                .assertComplete();

            wiremock.verify(getRequestedFor(urlPathEqualTo("/")).withRequestBody(equalTo(body)));
        }

        @Test
        void should_send_dynamic_body_to_callout_call() {
            wiremock.stubFor(get(urlEqualTo("/")).willReturn(aResponse().withStatus(200).withBody("{\"key\": \"a-value\"}")));

            String body = "dynamic body";
            var ctx = new ExecutionContextBuilder()
                .withComponent(Vertx.class, Vertx.vertx())
                .withComponent(Configuration.class, nodeConfiguration)
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

            wiremock.verify(getRequestedFor(urlPathEqualTo("/")).withRequestBody(equalTo(body)));
        }

        @ParameterizedTest
        @ValueSource(booleans = { true, false })
        void should_add_variables_in_context_when_callout_succeed(boolean exitOnError) {
            wiremock.stubFor(get(urlEqualTo("/")).willReturn(aResponse().withStatus(200).withBody("{\"key\": \"a-value\"}")));

            var ctx = new ExecutionContextBuilder()
                .withComponent(Vertx.class, Vertx.vertx())
                .withComponent(Configuration.class, nodeConfiguration)
                .request(aRequest().build())
                .build();

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

            assertThat(ctx.getAttributes())
                .containsEntry("callout1", "static")
                .containsEntry("callout2", "a-value")
                .containsKey("calloutResponse");
        }

        @ParameterizedTest
        @ValueSource(booleans = { true, false })
        void should_call_and_do_nothing_when_no_variables_defined(boolean exitOnError) {
            wiremock.stubFor(get(urlEqualTo("/")).willReturn(aResponse().withStatus(200).withBody("{\"key\": \"a-value\"}")));

            var ctx = new ExecutionContextBuilder()
                .withComponent(Vertx.class, Vertx.vertx())
                .withComponent(Configuration.class, nodeConfiguration)
                .request(aRequest().build())
                .build();

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
            assertThat(ctx.getAttributes()).containsOnlyKeys("calloutResponse");
            wiremock.verify(getRequestedFor(urlPathEqualTo("/")));
        }

        @Test
        void should_interrupt_when_exitOnError_and_use_default_message_when_no_error_content_defined() {
            wiremock.stubFor(get(urlEqualTo("/")).willReturn(aResponse().withStatus(400).withBody("Bad request")));

            var ctx = new ExecutionContextBuilder()
                .withComponent(Vertx.class, Vertx.vertx())
                .withComponent(Configuration.class, nodeConfiguration)
                .request(aRequest().build())
                .build();

            setMetricsOnContext(ctx);

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
                        .containsExactly(500, CalloutHttpPolicy.CALLOUT_EXIT_ON_ERROR, "Request is terminated.");
                    return true;
                });
        }

        @Test
        void should_interrupt_when_exitOnError_and_use_provided_configuration() {
            wiremock.stubFor(get(urlEqualTo("/")).willReturn(aResponse().withStatus(400).withBody("Bad request")));

            var ctx = new ExecutionContextBuilder()
                .withComponent(Vertx.class, Vertx.vertx())
                .withComponent(Configuration.class, nodeConfiguration)
                .request(aRequest().build())
                .build();

            setMetricsOnContext(ctx);

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
                        .containsExactly(502, CalloutHttpPolicy.CALLOUT_EXIT_ON_ERROR, "Error: Bad request");
                    return true;
                });
        }

        @ParameterizedTest
        @ValueSource(booleans = { true, false })
        void should_call_and_do_nothing_when_fire_and_forget_defined(boolean exitOnError) {
            wiremock.stubFor(get(urlEqualTo("/")).willReturn(aResponse().withStatus(200).withBody("{\"key\": \"a-value\"}")));

            var ctx = new ExecutionContextBuilder()
                .withComponent(Vertx.class, Vertx.vertx())
                .withComponent(Configuration.class, nodeConfiguration)
                .request(aRequest().build())
                .build();

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

            // When fireAndForget is true, no attributes are set
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
                .withComponent(Configuration.class, nodeConfiguration)
                .request(aRequest().header("X-Header2", "value2").build())
                .build();

            var configuration = CalloutHttpPolicyConfiguration.builder()
                .url(targetUrl(true))
                .method(HttpMethod.GET)
                .headers(
                    List.of(
                        new io.gravitee.policy.callout.configuration.HttpHeader("header1", "value1"),
                        new io.gravitee.policy.callout.configuration.HttpHeader("header2", "{#request.headers['X-Header2'][0]}")
                    )
                )
                .build();

            // Set SSL options to trust all and disable hostname verification for testing
            configuration.getSslOptions().setTrustAll(true);
            configuration.getSslOptions().setHostnameVerifier(false);

            policy(configuration).onResponse(ctx).test().awaitDone(30, TimeUnit.SECONDS).assertComplete();

            wiremock.verify(
                getRequestedFor(urlPathEqualTo("/")).withHeader("header1", equalTo("value1")).withHeader("header2", equalTo("value2"))
            );
        }

        @Test
        void should_call_target_using_system_proxy() {
            wiremock.stubFor(get(urlEqualTo("/")).willReturn(aResponse().withStatus(200).withBody("{\"key\": \"a-value\"}")));

            var ctx = new ExecutionContextBuilder()
                .withComponent(Vertx.class, Vertx.vertx())
                .withComponent(Configuration.class, nodeConfiguration)
                .request(aRequest().header("X-Header2", "value2").build())
                .build();

            var configuration = CalloutHttpPolicyConfiguration.builder()
                .useSystemProxy(true)
                .url(targetUrl(false))
                .method(HttpMethod.GET)
                .build();

            configuration.setUseSystemProxy(true);

            policy(configuration).onResponse(ctx).test().awaitDone(30, TimeUnit.SECONDS).assertComplete();

            wiremock.verify(getRequestedFor(urlPathEqualTo("/")));

            // Verify that the configuration methods were called with the correct arguments
            verify(nodeConfiguration, atLeastOnce()).getProperty("system.proxy.host");
            verify(nodeConfiguration, atLeastOnce()).getProperty("system.proxy.port");
            verify(nodeConfiguration, atLeastOnce()).getProperty("system.proxy.type");
            verify(nodeConfiguration, atLeastOnce()).getProperty("system.proxy.username");
            verify(nodeConfiguration, atLeastOnce()).getProperty("system.proxy.password");
        }
    }

    @Nested
    class OnMessageRequest {

        @ParameterizedTest
        @ValueSource(ints = { 1, 5, 10 })
        void should_make_http_calls_for_kafka_messages(int recordsCount) {
            wiremock.stubFor(get(urlEqualTo("/")).willReturn(aResponse().withStatus(200).withBody("{\"key\": \"a-value\"}")));

            KafkaMessageExecutionContext ctx = mock(KafkaMessageExecutionContext.class);
            KafkaMessageRequest request = mock(KafkaMessageRequest.class);

            List<KafkaMessage> messages = new ArrayList<>();
            for (int i = 0; i < recordsCount; i++) {
                KafkaMessage stubMessage = new KafkaMessageStub("test_" + i);
                messages.add(stubMessage);
            }

            when(request.onMessage(any())).thenAnswer(invocation -> {
                Function<KafkaMessage, Maybe<KafkaMessage>> function = invocation.getArgument(0);
                return Flowable.fromIterable(messages).flatMapMaybe(function::apply).ignoreElements(); // returns Completable
            });

            when(ctx.request()).thenReturn(request);

            TemplateEngine templateEngine = mock(TemplateEngine.class);
            // Fix: return a non-null TemplateContext mock
            when(templateEngine.getTemplateContext()).thenReturn(mock(io.gravitee.el.TemplateContext.class));
            when(templateEngine.eval(anyString(), any())).thenAnswer(invocation -> {
                String expression = invocation.getArgument(0);
                Class<?> targetType = invocation.getArgument(1);
                if (targetType == String.class) {
                    return Maybe.just(expression); // return the literal expression for static URL
                } else {
                    return Maybe.just(new Object());
                }
            });

            when(ctx.getTemplateEngine(any(KafkaMessage.class))).thenReturn(templateEngine);
            when(ctx.getTemplateEngine()).thenReturn(templateEngine);
            when(ctx.getComponent(Vertx.class)).thenReturn(Vertx.vertx());
            when(ctx.getComponent(Configuration.class)).thenReturn(nodeConfiguration);
            when(ctx.getTracer()).thenReturn(mock(Tracer.class));

            var configuration = CalloutHttpPolicyConfiguration.builder().url(targetUrl(false)).method(HttpMethod.GET).build();

            policy(configuration).onMessageRequest(ctx).test().awaitDone(30, TimeUnit.SECONDS).assertComplete();

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
            KafkaMessageResponse response = mock(KafkaMessageResponse.class);

            List<KafkaMessage> messages = new ArrayList<>();
            for (int i = 0; i < recordsCount; i++) {
                KafkaMessage stubMessage = new KafkaMessageStub("test_" + i);
                messages.add(stubMessage);
            }

            when(response.onMessage(any())).thenAnswer(invocation -> {
                Function<KafkaMessage, Maybe<KafkaMessage>> function = invocation.getArgument(0);
                return Flowable.fromIterable(messages).flatMapMaybe(function::apply).ignoreElements(); // returns Completable
            });

            when(ctx.response()).thenReturn(response);

            TemplateEngine templateEngine = mock(TemplateEngine.class);
            when(templateEngine.getTemplateContext()).thenReturn(mock(io.gravitee.el.TemplateContext.class));
            when(templateEngine.eval(anyString(), any())).thenAnswer(invocation -> {
                String expression = invocation.getArgument(0);
                Class<?> targetType = invocation.getArgument(1);
                if (targetType == String.class) {
                    return Maybe.just(expression);
                } else {
                    return Maybe.just(new Object());
                }
            });

            when(ctx.getTemplateEngine(any(KafkaMessage.class))).thenReturn(templateEngine);
            when(ctx.getTemplateEngine()).thenReturn(templateEngine);
            when(ctx.getComponent(Vertx.class)).thenReturn(Vertx.vertx());
            when(ctx.getComponent(Configuration.class)).thenReturn(nodeConfiguration);
            when(ctx.getTracer()).thenReturn(mock(Tracer.class));

            var configuration = CalloutHttpPolicyConfiguration.builder().url(targetUrl(false)).method(HttpMethod.GET).build();

            policy(configuration).onMessageResponse(ctx).test().awaitDone(30, TimeUnit.SECONDS).assertComplete();

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
