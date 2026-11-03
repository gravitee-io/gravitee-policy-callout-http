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

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static org.assertj.core.api.Assertions.assertThat;

import io.gravitee.apim.gateway.tests.sdk.annotations.DeployApi;
import io.gravitee.apim.gateway.tests.sdk.annotations.GatewayTest;
import io.gravitee.definition.model.ExecutionMode;
import io.gravitee.policy.callout.CountingCalloutTarget;
import io.vertx.core.http.HttpMethod;
import io.vertx.rxjava3.core.http.HttpClient;
import io.vertx.rxjava3.core.http.HttpClientRequest;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * @author Yann TAVERNIER (yann.tavernier at graviteesource.com)
 * @author GraviteeSource Team
 */
@GatewayTest(v2ExecutionMode = ExecutionMode.V4_EMULATION_ENGINE)
class CalloutHttpPolicyV4EmulationEngineIntegrationTest extends CalloutHttpPolicyV3IntegrationTest {

    private static final CountingCalloutTarget COUNTING_TARGET = new CountingCalloutTarget();

    @AfterAll
    static void closeCountingTarget() {
        COUNTING_TARGET.close();
    }

    @Override
    public void configurePlaceHolderVariables(Map<String, String> variables) {
        super.configurePlaceHolderVariables(variables);
        variables.put(CountingCalloutTarget.URL_PLACEHOLDER, COUNTING_TARGET.url());
    }

    @Test
    @DeployApi("/apis/v3/callout-http-lifetime-short.json")
    void should_open_a_new_callout_connection_once_the_idle_one_outlives_max_lifetime(HttpClient client) throws InterruptedException {
        wiremock.stubFor(get("/endpoint").willReturn(ok("response from backend")));
        COUNTING_TARGET.reset();

        for (int callout = 0; callout < 3; callout++) {
            if (callout > 0) {
                assertThat(COUNTING_TARGET.awaitConnectionClose(5, TimeUnit.SECONDS)).isTrue();
            }
            client
                .rxRequest(HttpMethod.GET, "/test-lifetime-short")
                .flatMap(HttpClientRequest::rxSend)
                .flatMapPublisher(response -> {
                    assertThat(response.statusCode()).isEqualTo(200);
                    return response.toFlowable();
                })
                .map(Object::toString)
                .test()
                .awaitDone(10, TimeUnit.SECONDS)
                .assertComplete()
                .assertValue(response -> {
                    assertThat(response).isEqualTo("response from callout");
                    return true;
                });
        }

        assertThat(COUNTING_TARGET.acceptedConnections()).isEqualTo(3);
    }
}
