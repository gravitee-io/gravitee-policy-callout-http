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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.gravitee.common.http.HttpMethod;
import io.gravitee.el.TemplateEngine;
import io.gravitee.gateway.reactive.api.ExecutionFailure;
import io.gravitee.gateway.reactive.api.context.kafka.KafkaMessageExecutionContext;
import io.gravitee.gateway.reactive.api.message.kafka.KafkaMessage;
import io.gravitee.gateway.reactive.api.tracing.Tracer;
import io.gravitee.gateway.reactive.core.context.interruption.InterruptionFailureException;
import io.gravitee.node.api.Node;
import io.gravitee.node.api.configuration.Configuration;
import io.gravitee.policy.callout.configuration.CalloutHttpPolicyConfiguration;
import io.gravitee.policy.callout.configuration.HttpClientOptions;
import io.gravitee.policy.callout.configuration.Variable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.processors.ReplayProcessor;
import io.vertx.core.http.PoolOptions;
import io.vertx.rxjava3.core.Vertx;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import test.ExecutionContextBuilder;
import test.stub.KafkaMessageRequestStub;
import test.stub.KafkaMessageResponseStub;
import test.stub.KafkaMessageStub;

class CalloutHttpPolicyV4Test {

    // Deterministic failing target: connection is refused immediately, unlike a DNS-dependent hostname.
    private static final String UNREACHABLE_URL = "http://127.0.0.1:1";

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
                .containsEntry("callout3", Map.of("key", "a-value"));
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
            var ctx = new ExecutionContextBuilder()
                .withComponent(Node.class, mock(Node.class))
                .withComponent(Vertx.class, Vertx.vertx())
                .request(aRequest().build())
                .build();

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
                    // The client only sees a generic message; the resolver failure detail stays in the cause and the logs.
                    assertThat(executionFailure.message()).isEqualTo("The HTTP callout could not be completed.");
                    assertThat(executionFailure.cause()).isInstanceOf(CalloutException.class);
                    return true;
                });
        }

        @Test
        void should_continue_when_fail_to_call_target_callout_and_exit_on_error_is_disabled() {
            var ctx = new ExecutionContextBuilder()
                .withComponent(Node.class, mock(Node.class))
                .withComponent(Vertx.class, Vertx.vertx())
                .request(aRequest().build())
                .build();

            policy(CalloutHttpPolicyConfiguration.builder().url(UNREACHABLE_URL).method(HttpMethod.GET).exitOnError(false).build())
                .onRequest(ctx)
                .test()
                .awaitDone(30, TimeUnit.SECONDS)
                .assertComplete();
        }

        @Test
        void should_interrupt_when_callout_request_is_invalid() {
            var ctx = new ExecutionContextBuilder()
                .withComponent(Node.class, mock(Node.class))
                .withComponent(Vertx.class, Vertx.vertx())
                .request(aRequest().build())
                .build();

            policy(CalloutHttpPolicyConfiguration.builder().url("not a url").method(HttpMethod.GET).exitOnError(true).build())
                .onRequest(ctx)
                .test()
                .awaitDone(30, TimeUnit.SECONDS)
                .assertError(e -> {
                    assertThat(e).isInstanceOf(InterruptionFailureException.class);
                    var executionFailure = ((InterruptionFailureException) e).getExecutionFailure();
                    assertThat(executionFailure.statusCode()).isEqualTo(500);
                    assertThat(executionFailure.key()).isEqualTo(CALLOUT_HTTP_ERROR);
                    // The client only sees a generic message; the malformed-URL detail stays in the cause and the logs.
                    assertThat(executionFailure.message()).isEqualTo("The HTTP callout could not be completed.");
                    assertThat(executionFailure.cause()).isInstanceOf(CalloutException.class);
                    return true;
                });
        }

        @Test
        void should_continue_when_callout_request_is_invalid_and_exit_on_error_is_disabled() {
            var ctx = new ExecutionContextBuilder()
                .withComponent(Node.class, mock(Node.class))
                .withComponent(Vertx.class, Vertx.vertx())
                .request(aRequest().build())
                .build();

            policy(CalloutHttpPolicyConfiguration.builder().url("not a url").method(HttpMethod.GET).exitOnError(false).build())
                .onRequest(ctx)
                .test()
                .awaitDone(30, TimeUnit.SECONDS)
                .assertComplete();
        }

        @ParameterizedTest
        @ValueSource(strings = { "url", "body", "header" })
        void should_interrupt_when_fail_to_evaluate_request_configuration(String configurationField) {
            var ctx = new ExecutionContextBuilder()
                .withComponent(Node.class, mock(Node.class))
                .withComponent(Vertx.class, Vertx.vertx())
                .request(aRequest().build())
                .build();
            var configurationBuilder = CalloutHttpPolicyConfiguration.builder()
                .url(targetUrl(false))
                .method(HttpMethod.GET)
                .exitOnError(true);
            var invalidExpression = "{#request.headers['x'][0";

            switch (configurationField) {
                case "url" -> configurationBuilder.url(invalidExpression);
                case "body" -> configurationBuilder.body(invalidExpression);
                case "header" -> configurationBuilder.headers(
                    List.of(new io.gravitee.policy.callout.configuration.HttpHeader("X-Token", invalidExpression))
                );
                default -> throw new IllegalArgumentException("Unsupported configuration field: " + configurationField);
            }

            policy(configurationBuilder.build())
                .onRequest(ctx)
                .test()
                .awaitDone(30, TimeUnit.SECONDS)
                .assertError(e -> {
                    assertThat(e).isInstanceOf(InterruptionFailureException.class);
                    var executionFailure = ((InterruptionFailureException) e).getExecutionFailure();
                    assertThat(executionFailure.statusCode()).isEqualTo(500);
                    assertThat(executionFailure.key()).isEqualTo(CALLOUT_HTTP_ERROR);
                    // The client only sees a generic message; the SpEL parse error (which echoes the configured
                    // expression) stays in the cause and the logs, not the response.
                    assertThat(executionFailure.message()).isEqualTo("The HTTP callout could not be completed.");
                    assertThat(executionFailure.cause())
                        .isInstanceOf(CalloutException.class)
                        .hasCauseInstanceOf(IllegalArgumentException.class);
                    return true;
                });
        }

        @Test
        void should_continue_when_fail_to_evaluate_request_configuration_and_exit_on_error_is_disabled() {
            var ctx = new ExecutionContextBuilder()
                .withComponent(Node.class, mock(Node.class))
                .withComponent(Vertx.class, Vertx.vertx())
                .request(aRequest().build())
                .build();

            policy(
                CalloutHttpPolicyConfiguration.builder()
                    .url(targetUrl(false))
                    .method(HttpMethod.GET)
                    .headers(List.of(new io.gravitee.policy.callout.configuration.HttpHeader("X-Token", "{#request.headers['x'][0")))
                    .exitOnError(false)
                    .build()
            )
                .onRequest(ctx)
                .test()
                .awaitDone(30, TimeUnit.SECONDS)
                .assertComplete();
        }

        @Test
        void should_interrupt_when_error_condition_evaluation_fails() {
            wiremock.stubFor(get(urlEqualTo("/")).willReturn(aResponse().withStatus(200).withBody("{\"key\": \"a-value\"}")));

            var ctx = new ExecutionContextBuilder()
                .withComponent(Node.class, mock(Node.class))
                .withComponent(Vertx.class, Vertx.vertx())
                .request(aRequest().build())
                .build();

            policy(
                CalloutHttpPolicyConfiguration.builder()
                    .url(targetUrl(false))
                    .method(HttpMethod.GET)
                    .exitOnError(true)
                    .errorCondition("{#calloutResponse.status !=")
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
                    assertThat(executionFailure.message()).isEqualTo("The HTTP callout could not be completed.");
                    assertThat(executionFailure.cause()).isInstanceOf(CalloutException.class);
                    return true;
                });
        }

        @Test
        void should_continue_when_variable_evaluation_fails_and_exit_on_error_is_disabled() {
            wiremock.stubFor(get(urlEqualTo("/")).willReturn(aResponse().withStatus(200).withBody("{\"key\": \"a-value\"}")));

            var ctx = new ExecutionContextBuilder()
                .withComponent(Node.class, mock(Node.class))
                .withComponent(Vertx.class, Vertx.vertx())
                .request(aRequest().build())
                .build();

            policy(
                CalloutHttpPolicyConfiguration.builder()
                    .url(targetUrl(false))
                    .method(HttpMethod.GET)
                    .exitOnError(false)
                    .variables(List.of(new Variable("bad", "{#calloutResponse.content.bad(")))
                    .build()
            )
                .onRequest(ctx)
                .test()
                .awaitDone(30, TimeUnit.SECONDS)
                .assertComplete();
        }

        @Test
        void should_interrupt_when_variable_evaluation_fails() {
            wiremock.stubFor(get(urlEqualTo("/")).willReturn(aResponse().withStatus(200).withBody("{\"key\": \"a-value\"}")));

            var ctx = new ExecutionContextBuilder()
                .withComponent(Node.class, mock(Node.class))
                .withComponent(Vertx.class, Vertx.vertx())
                .request(aRequest().build())
                .build();

            policy(
                CalloutHttpPolicyConfiguration.builder()
                    .url(targetUrl(false))
                    .method(HttpMethod.GET)
                    .exitOnError(true)
                    .errorCondition("{#calloutResponse.status != 200}")
                    .variables(List.of(new Variable("bad", "{#calloutResponse.content.bad(")))
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
                    assertThat(executionFailure.message()).isEqualTo("The HTTP callout could not be completed.");
                    assertThat(executionFailure.cause()).isInstanceOf(CalloutException.class);
                    return true;
                });
        }

        @Test
        void should_interrupt_when_error_content_evaluation_fails() {
            wiremock.stubFor(get(urlEqualTo("/")).willReturn(aResponse().withStatus(400).withBody("Bad request")));

            var ctx = new ExecutionContextBuilder()
                .withComponent(Node.class, mock(Node.class))
                .withComponent(Vertx.class, Vertx.vertx())
                .request(aRequest().build())
                .build();

            policy(
                CalloutHttpPolicyConfiguration.builder()
                    .url(targetUrl(false))
                    .method(HttpMethod.GET)
                    .exitOnError(true)
                    .errorCondition("{#calloutResponse.status != 200}")
                    .errorContent("{#calloutResponse.content.bad(")
                    .build()
            )
                .onRequest(ctx)
                .test()
                .awaitDone(30, TimeUnit.SECONDS)
                .assertError(e -> {
                    assertThat(e).isInstanceOf(InterruptionFailureException.class);
                    var executionFailure = ((InterruptionFailureException) e).getExecutionFailure();
                    assertThat(executionFailure.statusCode()).isEqualTo(500);
                    // Not CALLOUT_EXIT_ON_ERROR: the errorContent expression itself failed to evaluate, so no
                    // legitimate "exit on error" response could be built.
                    assertThat(executionFailure.key()).isEqualTo(CALLOUT_HTTP_ERROR);
                    assertThat(executionFailure.message()).isEqualTo("The HTTP callout could not be completed.");
                    assertThat(executionFailure.cause()).isInstanceOf(CalloutException.class);
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
        void should_log_and_continue_when_fire_and_forget_callout_fails() {
            var ctx = new ExecutionContextBuilder()
                .withComponent(Node.class, mock(Node.class))
                .withComponent(Vertx.class, Vertx.vertx())
                .request(aRequest().build())
                .build();

            try (var logs = captureLogs()) {
                policy(
                    CalloutHttpPolicyConfiguration.builder()
                        .url(UNREACHABLE_URL)
                        .method(HttpMethod.GET)
                        .fireAndForget(true)
                        // Pinned so this exercises fireAndForget's own branch of `tolerated`, not exitOnError's default.
                        .exitOnError(true)
                        .build()
                )
                    .onRequest(ctx)
                    .test()
                    .awaitDone(30, TimeUnit.SECONDS)
                    .assertComplete();

                // Matches on content rather than list position/size: other tests' detached fire-and-forget
                // subscriptions can still be logging to this process-global logger while this appender is attached.
                await()
                    .atMost(10, TimeUnit.SECONDS)
                    .untilAsserted(() ->
                        assertThat(logs.list).anySatisfy(event -> {
                            assertThat(event.getLevel()).isEqualTo(Level.WARN);
                            assertThat(event.getFormattedMessage()).contains(UNREACHABLE_URL);
                        })
                    );
            }
        }

        @Test
        void should_log_at_warn_level_when_exit_on_error_is_disabled() {
            var ctx = new ExecutionContextBuilder()
                .withComponent(Node.class, mock(Node.class))
                .withComponent(Vertx.class, Vertx.vertx())
                .request(aRequest().build())
                .build();

            try (var logs = captureLogs()) {
                policy(CalloutHttpPolicyConfiguration.builder().url(UNREACHABLE_URL).method(HttpMethod.GET).exitOnError(false).build())
                    .onRequest(ctx)
                    .test()
                    .awaitDone(30, TimeUnit.SECONDS)
                    .assertComplete();

                // Matches on content rather than an exact size: see the comment in the test above.
                assertThat(logs.list).anySatisfy(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.WARN);
                    assertThat(event.getFormattedMessage()).contains(UNREACHABLE_URL);
                });
            }
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

        @Test
        void should_forward_message_when_fail_to_call_target_callout_and_exit_on_error_is_disabled() {
            KafkaMessageExecutionContext ctx = mock(KafkaMessageExecutionContext.class);
            final KafkaMessageRequestStub request = new KafkaMessageRequestStub();
            when(ctx.request()).thenReturn(request);
            when(ctx.getTemplateEngine(any())).thenReturn(TemplateEngine.templateEngine());
            when(ctx.getComponent(Vertx.class)).thenReturn(Vertx.vertx());
            when(ctx.getTracer()).thenReturn(mock(Tracer.class));
            when(ctx.withLogger(any())).thenReturn(mock(org.slf4j.Logger.class));

            List<KafkaMessage> messages = List.of(new KafkaMessageStub("test_0"));

            policy(CalloutHttpPolicyConfiguration.builder().url(UNREACHABLE_URL).method(HttpMethod.GET).exitOnError(false).build())
                .onMessageRequest(ctx)
                .doOnComplete(() -> request.messages(Flowable.fromIterable(messages)))
                .test()
                .awaitDone(3, TimeUnit.SECONDS)
                .assertComplete();

            request.messages().test().awaitDone(3, TimeUnit.SECONDS).assertComplete().assertValueCount(1);
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

    @Nested
    class GetHttpClient {

        @Test
        void should_set_max_pool_size_from_configuration() {
            var mockVertx = mock(Vertx.class);
            var mockHttpClient = mock(io.vertx.rxjava3.core.http.HttpClientAgent.class);
            var poolOptionsCaptor = ArgumentCaptor.forClass(PoolOptions.class);
            when(mockVertx.createHttpClient(any(io.vertx.core.http.HttpClientOptions.class), poolOptionsCaptor.capture())).thenReturn(
                mockHttpClient
            );

            var ctx = new ExecutionContextBuilder().withComponent(Vertx.class, mockVertx).request(aRequest().build()).build();

            policy(
                CalloutHttpPolicyConfiguration.builder()
                    .url("http://localhost/")
                    .method(HttpMethod.GET)
                    .httpOptions(new HttpClientOptions(10))
                    .build()
            ).getHttpClient(ctx);

            assertThat(poolOptionsCaptor.getValue().getHttp1MaxSize()).isEqualTo(10);
        }

        @Test
        void should_set_max_pool_size_default_value() {
            var mockVertx = mock(Vertx.class);
            var mockHttpClient = mock(io.vertx.rxjava3.core.http.HttpClientAgent.class);
            var poolOptionsCaptor = ArgumentCaptor.forClass(PoolOptions.class);
            when(mockVertx.createHttpClient(any(io.vertx.core.http.HttpClientOptions.class), poolOptionsCaptor.capture())).thenReturn(
                mockHttpClient
            );

            var ctx = new ExecutionContextBuilder().withComponent(Vertx.class, mockVertx).request(aRequest().build()).build();

            policy(CalloutHttpPolicyConfiguration.builder().url("http://localhost/").method(HttpMethod.GET).build()).getHttpClient(ctx);

            assertThat(poolOptionsCaptor.getValue().getHttp1MaxSize()).isEqualTo(20);
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

    /**
     * Captures the policy's log events for assertions. Auto-closeable so tests can use
     * try-with-resources to detach the appender once done.
     */
    static final class LogCapture extends ListAppender<ILoggingEvent> implements AutoCloseable {

        private final Logger logger;
        private final Level originalLevel;

        private LogCapture(Logger logger) {
            this.logger = logger;
            // logback-test.xml pins io.gravitee to ERROR, which would silently drop WARN events
            // before any appender sees them; raise this logger's own level for the capture's lifetime.
            this.originalLevel = logger.getLevel();
            logger.setLevel(Level.ALL);
            // ListAppender's own list is a plain ArrayList: policy events arrive on Vert.x event-loop threads
            // while tests read `list` from the JUnit thread, with no happens-before edge between them.
            this.list = new CopyOnWriteArrayList<>();
        }

        @Override
        public void close() {
            logger.setLevel(originalLevel);
            logger.detachAppender(this);
            stop();
        }
    }

    LogCapture captureLogs() {
        var logger = (Logger) LoggerFactory.getLogger(CalloutHttpPolicy.class);
        var appender = new LogCapture(logger);
        appender.start();
        logger.addAppender(appender);
        return appender;
    }
}
