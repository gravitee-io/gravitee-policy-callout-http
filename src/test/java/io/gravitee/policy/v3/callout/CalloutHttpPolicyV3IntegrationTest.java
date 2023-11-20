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
package io.gravitee.policy.v3.callout;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.gravitee.apim.gateway.tests.sdk.AbstractPolicyTest;
import io.gravitee.apim.gateway.tests.sdk.annotations.DeployApi;
import io.gravitee.apim.gateway.tests.sdk.annotations.GatewayTest;
import io.gravitee.apim.gateway.tests.sdk.policy.PolicyBuilder;
import io.gravitee.common.http.HttpStatusCode;
import io.gravitee.definition.model.Api;
import io.gravitee.definition.model.ExecutionMode;
import io.gravitee.plugin.policy.PolicyPlugin;
import io.gravitee.policy.callout.CalloutHttpPolicy;
import io.gravitee.policy.callout.configuration.CalloutHttpPolicyConfiguration;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.rxjava3.core.buffer.Buffer;
import io.vertx.rxjava3.core.http.HttpClient;
import io.vertx.rxjava3.core.http.HttpClientRequest;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * @author Yann TAVERNIER (yann.tavernier at graviteesource.com)
 * @author GraviteeSource Team
 */
@GatewayTest(v2ExecutionMode = ExecutionMode.V3)
class CalloutHttpPolicyV3IntegrationTest extends AbstractPolicyTest<CalloutHttpPolicy, CalloutHttpPolicyConfiguration> {

    public static final String LOCALHOST = "localhost:";
    public static final String CALLOUT_BASE_URL = LOCALHOST + "8089";

    @RegisterExtension
    static WireMockExtension calloutServer = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    /**
     * Override Callout policy URL to use the dynamic port from {@link CalloutHttpPolicyV3IntegrationTest#calloutServer}
     * @param api is the api to apply this function code
     */
    @Override
    public void configureApi(Api api) {
        api
            .getFlows()
            .forEach(flow ->
                flow
                    .getPre()
                    .stream()
                    .filter(step -> policyName().equals(step.getPolicy()))
                    .forEach(step ->
                        step.setConfiguration(step.getConfiguration().replace(CALLOUT_BASE_URL, LOCALHOST + calloutServer.getPort()))
                    )
            );
    }

    @Override
    public void configurePolicies(Map<String, PolicyPlugin> policies) {
        policies.put("copy-callout-attribute", PolicyBuilder.build("copy-callout-attribute", CopyCalloutAttributePolicy.class));
    }

    @Test
    @DisplayName("Should do callout and set response as attribute")
    @DeployApi("/apis/v3/callout-http.json")
    void shouldDoCalloutAndSetResponseAsAttribute(HttpClient client) {
        wiremock.stubFor(get("/endpoint").willReturn(ok("response from backend")));
        calloutServer.stubFor(get("/callout").willReturn(ok("response from callout")));

        var obs = client
            .rxRequest(HttpMethod.GET, "/test")
            .flatMap(HttpClientRequest::rxSend)
            .flatMapPublisher(response -> {
                assertThat(response.statusCode()).isEqualTo(200);
                return response.toFlowable();
            })
            .test();

        obs
            .awaitDone(30, TimeUnit.SECONDS)
            .assertComplete()
            .assertValue(response -> {
                assertThat(response).hasToString("response from callout");
                return true;
            })
            .assertNoErrors();

        wiremock.verify(getRequestedFor(urlPathEqualTo("/endpoint")));
        calloutServer.verify(getRequestedFor(urlPathEqualTo("/callout")).withHeader("X-Callout", equalTo("calloutHeader")));
    }

    @Test
    @DisplayName("Should call callout endpoint with proper body when it contains accents")
    @DeployApi("/apis/v3/callout-http-post-with-accents.json")
    void shouldDoCalloutAndSetResponseWithAccentAsAttribute(HttpClient client) {
        wiremock.stubFor(post("/endpoint").willReturn(ok("réponse from backend")));
        calloutServer.stubFor(post("/callout").willReturn(ok("response from callout")));

        var obs = client
            .rxRequest(HttpMethod.POST, "/test")
            .flatMap(req -> req.rxSend(Buffer.buffer(new JsonObject().put("clé", "value").put("key", "välæur").encode())))
            .flatMapPublisher(response -> {
                assertThat(response.statusCode()).isEqualTo(200);
                return response.toFlowable();
            })
            .test();

        obs
            .awaitDone(30, TimeUnit.SECONDS)
            .assertComplete()
            .assertValue(response -> {
                assertThat(response).hasToString("réponse from backend");
                return true;
            })
            .assertNoErrors();

        wiremock.verify(postRequestedFor(urlPathEqualTo("/endpoint")));
        calloutServer.verify(
            postRequestedFor(urlPathEqualTo("/callout"))
                .withoutHeader(HttpHeaders.TRANSFER_ENCODING.toString())
                .withHeader(HttpHeaders.CONTENT_LENGTH.toString(), equalTo("33"))
                .withRequestBody(equalToJson("{\"clé\":\"value\", \"key\":\"välæur\"}"))
        );
    }

    @Test
    @DisplayName("Should do callout Fire and Forget")
    @DeployApi("/apis/v3/callout-http-fire-and-forget.json")
    void shouldDoCalloutFireAndForget(HttpClient client) {
        wiremock.stubFor(get("/endpoint").willReturn(ok("response from backend")));
        calloutServer.stubFor(get("/callout").willReturn(ok("response from callout")));

        var obs = client
            .rxRequest(HttpMethod.GET, "/test")
            .flatMap(HttpClientRequest::rxSend)
            .flatMapPublisher(response -> {
                assertThat(response.statusCode()).isEqualTo(200);
                return response.toFlowable();
            })
            .test();

        obs
            .awaitDone(30, TimeUnit.SECONDS)
            .assertComplete()
            .assertValue(response -> {
                assertThat(response).hasToString(CopyCalloutAttributePolicy.NO_CALLOUT_CONTENT_ATTRIBUTE);
                return true;
            })
            .assertNoErrors();

        wiremock.verify(getRequestedFor(urlPathEqualTo("/endpoint")));
        calloutServer.verify(getRequestedFor(urlPathEqualTo("/callout")).withHeader("X-Callout", equalTo("calloutHeader")));
    }

    @Test
    @DisplayName("Should do callout on invalid target and answer custom response")
    @DeployApi("/apis/v3/callout-http-invalid-target.json")
    void shouldDoCalloutInvalidTarget(HttpClient client) {
        wiremock.stubFor(get("/endpoint").willReturn(ok("response from backend")));
        calloutServer.stubFor(get("/callout").willReturn(aResponse().withStatus(501).withBody("callout backend not implemented")));

        var obs = client
            .rxRequest(HttpMethod.GET, "/test")
            .flatMap(HttpClientRequest::rxSend)
            .flatMapPublisher(response -> {
                assertThat(response.statusCode()).isEqualTo(HttpStatusCode.NOT_IMPLEMENTED_501);
                return response.toFlowable();
            })
            .test();

        obs
            .awaitDone(30, TimeUnit.SECONDS)
            .assertComplete()
            .assertValue(response -> {
                assertThat(response).hasToString("errorContent");
                return true;
            })
            .assertNoErrors();

        wiremock.verify(0, getRequestedFor(urlPathEqualTo("/endpoint")));
        calloutServer.verify(getRequestedFor(urlPathEqualTo("/callout")).withHeader("X-Callout", equalTo("calloutHeader")));
    }
}
