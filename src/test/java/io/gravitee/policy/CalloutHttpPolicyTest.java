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

import com.github.tomakehurst.wiremock.extension.Parameters;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import io.gravitee.common.http.HttpMethod;
import io.gravitee.common.http.HttpStatusCode;
import io.gravitee.common.util.LinkedMultiValueMap;
import io.gravitee.common.util.MultiValueMap;
import io.gravitee.el.spel.SpelTemplateEngineFactory;
import io.gravitee.gateway.api.ExecutionContext;
import io.gravitee.gateway.api.Request;
import io.gravitee.gateway.api.RequestWrapper;
import io.gravitee.gateway.api.Response;
import io.gravitee.policy.api.PolicyChain;
import io.gravitee.policy.api.PolicyResult;
import io.gravitee.policy.callout.CalloutHttpPolicy;
import io.gravitee.policy.callout.configuration.CalloutHttpPolicyConfiguration;
import io.gravitee.policy.callout.configuration.PolicyScope;
import io.gravitee.policy.callout.configuration.Variable;
import io.vertx.core.Vertx;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.core.env.Environment;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.*;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
@RunWith(MockitoJUnitRunner.class)
public class CalloutHttpPolicyTest {

    @Rule
    public WireMockRule wireMockRule = new WireMockRule(wireMockConfig().dynamicPort().extensions(CountDownWebhook.class));

    @Mock
    private ExecutionContext executionContext;

    @Mock
    private Request request;

    @Mock
    private Response response;

    private PolicyChain policyChain;

    @Mock
    private CalloutHttpPolicyConfiguration configuration;

    @Mock
    private Environment env;

    @Before
    public void init() {
        reset(configuration, executionContext, request, response, env);
        when(executionContext.getComponent(Vertx.class)).thenReturn(Vertx.vertx());
        when(executionContext.getComponent(Environment.class)).thenReturn(env);
        when(executionContext.getTemplateEngine()).thenReturn(new SpelTemplateEngineFactory().templateEngine());

        Request request = new RequestWrapper(mock(Request.class)) {
            @Override
            public MultiValueMap<String, String> parameters() {
                LinkedMultiValueMap<String, String> parameters = new LinkedMultiValueMap<>();
                parameters.add("param", "content");
                return parameters;
            }
        };
        when(executionContext.request()).thenReturn(request);
        CountDownWebhook.lock = null;
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
        this.policyChain = spy(new CountDownPolicyChain(lock));

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
        this.policyChain = spy(new CountDownPolicyChain(lock));

        new CalloutHttpPolicy(configuration).onRequest(request, response, executionContext, policyChain);

        lock.await(1000, TimeUnit.MILLISECONDS);

        verify(policyChain, times(1)).doNext(request, response);

        verify(getRequestedFor(urlEqualTo("/")));
    }

    @Test
    public void shouldProcessRequest_withProxy() throws Exception {
        stubFor(get(urlEqualTo("/"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("{\"key\": \"value\"}")));

        when(configuration.getMethod()).thenReturn(HttpMethod.GET);
        when(configuration.isUseSystemProxy()).thenReturn(true);
        when(configuration.getUrl()).thenReturn("http://localhost:" + wireMockRule.port() + "/");

        when(env.containsProperty(anyString())).thenReturn(true);
        when(env.getProperty(anyString())).thenReturn("localhost-proxy", "3129", "HTTP", "null", "null");

        final CountDownLatch lock = new CountDownLatch(1);
        this.policyChain = spy(new CountDownPolicyChain(lock));

        new CalloutHttpPolicy(configuration).onRequest(request, response, executionContext, policyChain);

        lock.await(1000, TimeUnit.MILLISECONDS);

        verify(policyChain).doNext(request, response);
        verify(env).getProperty("system.proxy.port");
        verify(env).getProperty("system.proxy.type");
        verify(env).getProperty("system.proxy.host");
        verify(env).getProperty("system.proxy.username");
        verify(env).getProperty("system.proxy.password");
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
        this.policyChain = spy(new CountDownPolicyChain(lock));

        new CalloutHttpPolicy(configuration).onRequest(request, response, executionContext, policyChain);

        lock.await(1000, TimeUnit.MILLISECONDS);

        verify(executionContext, times(1)).setAttribute(eq("my-var"), eq("value"));
        verify(policyChain, times(1)).doNext(request, response);

        verify(getRequestedFor(urlEqualTo("/")));
    }

    @Test
    public void shouldProcessPostRequest() throws Exception {
        stubFor(post(urlEqualTo("/"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("{\"key\": \"value\"}")));

        when(configuration.getMethod()).thenReturn(HttpMethod.POST);
        when(configuration.getBody()).thenReturn("a body");
        when(configuration.getUrl()).thenReturn("http://localhost:" + wireMockRule.port() + "/");

        final CountDownLatch lock = new CountDownLatch(1);
        this.policyChain = spy(new CountDownPolicyChain(lock));

        new CalloutHttpPolicy(configuration).onRequest(request, response, executionContext, policyChain);

        lock.await(1000, TimeUnit.MILLISECONDS);

        verify(policyChain, times(1)).doNext(request, response);

        verify(postRequestedFor(urlEqualTo("/")).withRequestBody(equalTo("a body")));
    }

    @Test
    public void shouldProcessPostRequest_nullBody() throws Exception {
        stubFor(post(urlEqualTo("/"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("{\"key\": \"value\"}")));

        when(configuration.getMethod()).thenReturn(HttpMethod.POST);
        when(configuration.getBody()).thenReturn(null);
        when(configuration.getUrl()).thenReturn("http://localhost:" + wireMockRule.port() + "/");

        final CountDownLatch lock = new CountDownLatch(1);
        this.policyChain = spy(new CountDownPolicyChain(lock));

        new CalloutHttpPolicy(configuration).onRequest(request, response, executionContext, policyChain);

        lock.await(1000, TimeUnit.MILLISECONDS);

        verify(policyChain, times(1)).doNext(request, response);

        verify(postRequestedFor(urlEqualTo("/")));
    }

    @Test
    public void shouldProcessPostRequest_nullEvaluatedBody() throws Exception {
        stubFor(post(urlEqualTo("/"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("{\"key\": \"value\"}")));

        when(configuration.getMethod()).thenReturn(HttpMethod.POST);
        when(configuration.getBody()).thenReturn("{#itIsResolvedToNull}");
        when(configuration.getUrl()).thenReturn("http://localhost:" + wireMockRule.port() + "/");

        final CountDownLatch lock = new CountDownLatch(1);
        this.policyChain = spy(new CountDownPolicyChain(lock));

        new CalloutHttpPolicy(configuration).onRequest(request, response, executionContext, policyChain);

        lock.await(1000, TimeUnit.MILLISECONDS);

        verify(policyChain, times(1)).doNext(request, response);

        verify(postRequestedFor(urlEqualTo("/")));
    }

    @Test
    public void shouldProcessRequest_withMainRequestVariable_issue4277() throws Exception {
        when(configuration.getScope()).thenReturn(PolicyScope.REQUEST);
        executeProcess_withMainRequestVariable();
    }

    @Test
    public void shouldProcessResponse_withMainRequestVariable_issue4277() throws Exception {
        when(configuration.getScope()).thenReturn(PolicyScope.RESPONSE);
        executeProcess_withMainRequestVariable();
    }

    private void executeProcess_withMainRequestVariable() throws InterruptedException {
        stubFor(get(urlEqualTo("/content"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("{\"key\": \"value\"}")));

        when(configuration.getMethod()).thenReturn(HttpMethod.GET);
        when(configuration.getUrl()).thenReturn("http://localhost:" + wireMockRule.port() + "/{#request.params['param']}");
        when(configuration.getVariables()).thenReturn(Collections.singletonList(
                new Variable("my-var", "{#jsonPath(#calloutResponse.content, '$.key')}")));

        final CountDownLatch lock = new CountDownLatch(1);
        this.policyChain = spy(new CountDownPolicyChain(lock));

        if (configuration.getScope() == PolicyScope.RESPONSE) {
            new CalloutHttpPolicy(configuration).onResponse(request, response, executionContext, policyChain);
        } else {
            new CalloutHttpPolicy(configuration).onRequest(request, response, executionContext, policyChain);
        }

        lock.await(1000, TimeUnit.MILLISECONDS);

        verify(executionContext, times(1)).setAttribute(eq("my-var"), eq("value"));
        verify(policyChain, times(1)).doNext(request, response);

        verify(getRequestedFor(urlEqualTo("/content")));
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
        this.policyChain = spy(new CountDownPolicyChain(lock));

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
                new Variable("my-headers", "{#calloutResponse.headers['Header']}")));

        final CountDownLatch lock = new CountDownLatch(1);
        this.policyChain = spy(new CountDownPolicyChain(lock));

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
        when(configuration.getUrl()).thenReturn("http://" + UUID.randomUUID() + ":8080/");

        final CountDownLatch lock = new CountDownLatch(1);
        this.policyChain = spy(new CountDownPolicyChain(lock));

        new CalloutHttpPolicy(configuration).onRequest(request, response, executionContext, policyChain);

        lock.await(61, TimeUnit.SECONDS); // vertx DEFAULT_CONNECT_TIMEOUT is 60 seconds

        verify(policyChain, never()).failWith(any());
        verify(policyChain).doNext(any(), any());
    }

    @Test
    public void shouldFireAndForget_noVariableSet() throws Exception {
        final CountDownLatch lock = new CountDownLatch(1);
        CountDownWebhook.lock = lock;
        stubFor(get(urlEqualTo("/"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("{\"key\": \"value\"}")
                        .withHeader("Header", "value1", "value2"))
                .withPostServeAction("CountDownWebhook", Parameters.empty())
        );

        when(configuration.isFireAndForget()).thenReturn(true);
        when(configuration.getMethod()).thenReturn(HttpMethod.GET);
        when(configuration.getUrl()).thenReturn("http://localhost:" + wireMockRule.port() + "/");

        this.policyChain = spy(new NoOpPolicyChain());

        new CalloutHttpPolicy(configuration).onRequest(request, response, executionContext, policyChain);

        assertTrue(lock.await(1000, TimeUnit.MILLISECONDS));

        verify(policyChain, never()).failWith(any());
        verify(policyChain).doNext(any(), any());
        // We do not expect variables to be set in fire & forget mode.
        verify(executionContext, never()).setAttribute(anyString(), anyString());
        verify(getRequestedFor(urlEqualTo("/")));
    }

    @Test
    public void shouldFireAndForget_noExitOnError() throws Exception {
        final CountDownLatch lock = new CountDownLatch(1);
        CountDownWebhook.lock = lock;

        stubFor(get(urlEqualTo("/"))
                .willReturn(aResponse()
                        .withStatus(400))
                .withPostServeAction("CountDownWebhook", Parameters.empty()));

        when(configuration.isFireAndForget()).thenReturn(true);
        when(configuration.getMethod()).thenReturn(HttpMethod.GET);
        when(configuration.getUrl()).thenReturn("http://localhost:" + wireMockRule.port() + "/");

        this.policyChain = spy(new NoOpPolicyChain());

        new CalloutHttpPolicy(configuration).onRequest(request, response, executionContext, policyChain);

        lock.await(1000, TimeUnit.MILLISECONDS);

        verify(policyChain, never()).failWith(any());
        verify(policyChain).doNext(any(), any());
        verify(getRequestedFor(urlEqualTo("/")));
        verify(configuration, never()).isExitOnError();
    }




    class CountDownPolicyChain implements PolicyChain {
        private final CountDownLatch lock;

        public CountDownPolicyChain(CountDownLatch lock) {
            this.lock = lock;
        }

        @Override
        public void doNext(Request request, Response response) {
            lock.countDown();
        }

        @Override
        public void failWith(PolicyResult policyResult) {
            lock.countDown();
        }

        @Override
        public void streamFailWith(PolicyResult policyResult) {
            lock.countDown();
        }
    }


    class NoOpPolicyChain implements PolicyChain {

        @Override
        public void doNext(Request request, Response response) {

        }

        @Override
        public void failWith(PolicyResult policyResult) {

        }

        @Override
        public void streamFailWith(PolicyResult policyResult) {

        }
    }
}