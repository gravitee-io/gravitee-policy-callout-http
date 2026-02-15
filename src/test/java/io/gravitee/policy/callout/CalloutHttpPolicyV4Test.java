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

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static io.vertx.core.http.HttpMethod.GET;
import static io.vertx.core.http.HttpMethod.POST;
import static org.mockito.Mockito.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.gravitee.common.http.HttpMethod;
import io.gravitee.el.TemplateContext;
import io.gravitee.el.TemplateEngine;
import io.gravitee.gateway.reactive.api.context.http.HttpPlainExecutionContext;
import io.gravitee.gateway.reactive.api.context.http.HttpPlainRequest;
import io.gravitee.gateway.reactive.api.context.http.HttpPlainResponse;
import io.gravitee.node.api.configuration.Configuration;
import io.gravitee.node.api.opentelemetry.Tracer;
import io.gravitee.plugin.configurations.http.HttpClientOptions;
import io.gravitee.policy.callout.configuration.CalloutHttpPolicyConfiguration;
import io.gravitee.policy.callout.configuration.HttpHeader;
import io.gravitee.policy.callout.configuration.Variable;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import io.vertx.rxjava3.core.Vertx;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
@ExtendWith(MockitoExtension.class)
class CalloutHttpPolicyV4Test {

    private static final WireMockServer wireMockServer = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    @Mock
    private HttpPlainExecutionContext ctx;

    @Mock
    private HttpPlainRequest request;

    @Mock
    private HttpPlainResponse response;

    @Mock
    private TemplateEngine templateEngine;

    @Mock
    private TemplateContext templateContext;

    @Mock
    private Configuration nodeConfiguration;

    @Mock
    private Tracer tracer;

    private CalloutHttpPolicy cut;
    private CalloutHttpPolicyConfiguration configuration;

    @BeforeAll
    static void beforeAll() {
        wireMockServer.start();
    }

    @AfterAll
    static void afterAll() {
        wireMockServer.stop();
    }

    @BeforeEach
    void setUp() {
        configuration = new CalloutHttpPolicyConfiguration();
        HttpClientOptions httpOptions = new HttpClientOptions();
        httpOptions.setConnectTimeout(1000L);
        httpOptions.setReadTimeout(1000L);
        configuration.setHttp(httpOptions);

        String url = "http://localhost:" + wireMockServer.port();
        configuration.setUrl(url);
        configuration.setMethod(HttpMethod.GET);

        cut = new CalloutHttpPolicy(configuration);

        lenient().when(ctx.request()).thenReturn(request);
        lenient().when(ctx.response()).thenReturn(response);
        lenient().when(ctx.getTemplateEngine()).thenReturn(templateEngine);
        lenient().when(templateEngine.getTemplateContext()).thenReturn(templateContext);
        // Important: Mock evalNow used in factory
        lenient().when(templateEngine.evalNow(url, String.class)).thenReturn(url);

        lenient().when(ctx.getComponent(Configuration.class)).thenReturn(nodeConfiguration);
        lenient().when(ctx.getComponent(Vertx.class)).thenReturn(Vertx.vertx());
        // lenient().when(ctx.getTracer()).thenReturn(tracer);
    }

    @Test
    void should_execute_callout_and_populate_context_variable() {
        wireMockServer.stubFor(get(urlEqualTo("/")).willReturn(aResponse().withStatus(200).withBody("response body")));

        configuration.setVariables(List.of(new Variable("my-var", "{#jsonPath(#calloutResponse.content, '$.key')}")));

        when(templateEngine.eval("http://localhost:" + wireMockServer.port(), String.class)).thenReturn(
            Maybe.just("http://localhost:" + wireMockServer.port())
        );
        when(templateEngine.eval(anyString(), eq(String.class))).thenReturn(Maybe.just("evaluated-value"));

        cut.onRequest(ctx).test().awaitDone(5, TimeUnit.SECONDS).assertComplete();

        verify(ctx).setAttribute("my-var", "evaluated-value");
        verify(templateContext).setVariable(eq("calloutResponse"), any(CalloutResponse.class));
    }

    @Test
    void should_interrupt_execution_on_error() {
        wireMockServer.stubFor(get(urlEqualTo("/error")).willReturn(aResponse().withStatus(500)));

        String errorUrl = "http://localhost:" + wireMockServer.port() + "/error";
        configuration.setUrl(errorUrl);
        configuration.setExitOnError(true);
        configuration.setErrorCondition("true");

        when(templateEngine.evalNow(errorUrl, String.class)).thenReturn(errorUrl);
        when(templateEngine.eval(anyString(), eq(String.class))).thenReturn(Maybe.just(errorUrl));
        //   Mockito.<Maybe<Boolean>>when(templateEngine.eval("true", Boolean.class)).thenReturn(Single.just(true));
        when(ctx.interruptWith(any())).thenReturn(Completable.complete());

        cut.onRequest(ctx).test().awaitDone(5, TimeUnit.SECONDS).assertComplete();

        verify(ctx).interruptWith(argThat(failure -> failure.statusCode() == 500));
    }
}
