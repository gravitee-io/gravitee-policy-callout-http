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
package io.gravitee.policy;

import com.github.tomakehurst.wiremock.junit.WireMockRule;
import io.gravitee.common.http.HttpMethod;
import io.gravitee.common.http.HttpStatusCode;
import io.gravitee.el.spel.SpelTemplateEngineFactory;
import io.gravitee.gateway.api.ExecutionContext;
import io.gravitee.gateway.api.Request;
import io.gravitee.gateway.api.Response;
import io.gravitee.policy.api.PolicyChain;
import io.gravitee.policy.callout.CalloutHttpPolicy;
import io.gravitee.policy.callout.configuration.CalloutHttpPolicyConfiguration;
import io.gravitee.policy.callout.configuration.Variable;
import io.vertx.core.Vertx;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.*;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
@RunWith(MockitoJUnitRunner.class)
public class CalloutHttpPolicyTest {

    @Rule
    public WireMockRule wireMockRule = new WireMockRule(wireMockConfig().dynamicPort());

    @Mock
    private ExecutionContext executionContext;

    @Mock
    private Request request;

    @Mock
    private Response response;

    @Mock
    private PolicyChain policyChain;

    @Mock
    private CalloutHttpPolicyConfiguration configuration;

    @Before
    public void init() {
        when(executionContext.getComponent(Vertx.class)).thenReturn(Vertx.vertx());
        when(executionContext.getTemplateEngine()).thenReturn(new SpelTemplateEngineFactory().templateEngine());
    }

    @Test
    @Ignore
    public void shouldNotProcessRequest_invalidTarget() throws Exception {
        final String invalidTarget = "http://tsohlacollocalhost:" + wireMockRule.port() + '/';
        stubFor(get(urlEqualTo(invalidTarget))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withBody("{\"key\": \"value\"}")));

        when(configuration.getMethod()).thenReturn(HttpMethod.GET);
        when(configuration.getUrl()).thenReturn(invalidTarget);

        final CountDownLatch lock = new CountDownLatch(1);

        new CalloutHttpPolicy(configuration).onRequest(request, response, executionContext, policyChain);

        lock.await(1000, TimeUnit.MILLISECONDS);

        verify(policyChain, times(1)).failWith(argThat(
                result -> result.statusCode() == HttpStatusCode.INTERNAL_SERVER_ERROR_500));
    }

    @Test
    public void shouldProcessRequest_emptyVariable() throws Exception {
        stubFor(get(urlEqualTo("/"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("{\"key\": \"value\"}")));

        when(configuration.getMethod()).thenReturn(HttpMethod.GET);
        when(configuration.getUrl()).thenReturn("http://localhost:" + wireMockRule.port() + "/");

        final CountDownLatch lock = new CountDownLatch(1);

        new CalloutHttpPolicy(configuration).onRequest(request, response, executionContext, policyChain);

        lock.await(1000, TimeUnit.MILLISECONDS);

        verify(policyChain, times(1)).doNext(request, response);

        verify(getRequestedFor(urlEqualTo("/")));
    }

    @Test
    public void shouldProcessRequest_withVariable() throws Exception {
        stubFor(get(urlEqualTo("/"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("{\"key\": \"value\"}")));

        when(configuration.getMethod()).thenReturn(HttpMethod.GET);
        when(configuration.getUrl()).thenReturn("http://localhost:" + wireMockRule.port() + "/");
        when(configuration.getVariables()).thenReturn(Collections.singletonList(
                new Variable("my-var", "{#jsonPath(#calloutResponse.content, '$.key')}")));

        final CountDownLatch lock = new CountDownLatch(1);

        new CalloutHttpPolicy(configuration).onRequest(request, response, executionContext, policyChain);

        lock.await(1000, TimeUnit.MILLISECONDS);

        verify(executionContext, times(1)).setAttribute(eq("my-var"), eq("value"));
        verify(policyChain, times(1)).doNext(request, response);

        verify(getRequestedFor(urlEqualTo("/")));
    }

    @Test
    public void shouldNotProcessRequest_errorCondition() throws Exception {
        stubFor(get(urlEqualTo("/"))
                .willReturn(aResponse()
                        .withStatus(400)));

        when(configuration.getMethod()).thenReturn(HttpMethod.GET);
        when(configuration.getUrl()).thenReturn("http://localhost:" + wireMockRule.port() + "/");
        when(configuration.isExitOnError()).thenReturn(true);
        when(configuration.getErrorCondition()).thenReturn("{#calloutResponse.status >= 400 and #calloutResponse.status <= 599}");
        when(configuration.getErrorContent()).thenReturn("This is an error content");
        when(configuration.getErrorStatusCode()).thenReturn(HttpStatusCode.INTERNAL_SERVER_ERROR_500);

        final CountDownLatch lock = new CountDownLatch(1);

        new CalloutHttpPolicy(configuration).onRequest(request, response, executionContext, policyChain);

        lock.await(1000, TimeUnit.MILLISECONDS);

        verify(policyChain, times(1)).failWith(argThat(
                result -> result.statusCode() == HttpStatusCode.INTERNAL_SERVER_ERROR_500 &&
                result.message().equals("This is an error content")));

        verify(getRequestedFor(urlEqualTo("/")));
    }

    @Test
    public void shouldProcessRequest_withHeaders() throws Exception {
        stubFor(get(urlEqualTo("/"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("{\"key\": \"value\"}")
                        .withHeader("Header", "value1", "value2")));

        when(configuration.getMethod()).thenReturn(HttpMethod.GET);
        when(configuration.getUrl()).thenReturn("http://localhost:" + wireMockRule.port() + "/");
        when(configuration.getVariables()).thenReturn(Collections.singletonList(
                new Variable("my-headers", "{#jsonPath(#calloutResponse.headers, '$.Header')}")));

        final CountDownLatch lock = new CountDownLatch(1);

        new CalloutHttpPolicy(configuration).onRequest(request, response, executionContext, policyChain);

        lock.await(1000, TimeUnit.MILLISECONDS);

        verify(executionContext, times(1)).setAttribute(eq("my-headers"), eq("value1,value2"));
        verify(policyChain, times(1)).doNext(request, response);

        verify(getRequestedFor(urlEqualTo("/")));
    }

    @Test
    public void shouldIgnoreConnectionError() throws Exception {
        when(configuration.getMethod()).thenReturn(HttpMethod.GET);
        when(configuration.isExitOnError()).thenReturn(false);
        when(configuration.getUrl()).thenReturn("http://unknownhost:65534/");

        final CountDownLatch lock = new CountDownLatch(1);

        new CalloutHttpPolicy(configuration).onRequest(request, response, executionContext, policyChain);

        lock.await(1000, TimeUnit.MILLISECONDS);

        verify(policyChain, never()).failWith(any());
        verify(policyChain).doNext(any(), any());
    }
}