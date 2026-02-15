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
package io.gravitee.policy.callout.configuration;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.gravitee.common.http.HttpMethod;
import io.gravitee.common.http.HttpStatusCode;
import io.gravitee.plugin.configurations.http.HttpClientOptions;
import io.gravitee.plugin.configurations.http.HttpProxyOptions;
import io.gravitee.plugin.configurations.ssl.SslOptions;
import io.gravitee.policy.api.PolicyConfiguration;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class CalloutHttpPolicyConfiguration implements PolicyConfiguration {

    @Builder.Default
    private PolicyScope scope = PolicyScope.REQUEST;

    private String url;

    @Builder.Default
    private List<HttpHeader> headers = new ArrayList<>();

    private String body;

    private HttpMethod method;

    @Builder.Default
    private List<Variable> variables = new ArrayList<>();

    private boolean exitOnError;

    private boolean fireAndForget;

    private String errorCondition;

    @Builder.Default
    private int errorStatusCode = HttpStatusCode.INTERNAL_SERVER_ERROR_500;

    private String errorContent;

    @JsonProperty("http")
    private HttpClientOptions http = new HttpClientOptions();

    @JsonProperty("ssl")
    private SslOptions ssl = new SslOptions();

    @JsonProperty("proxy")
    private HttpProxyOptions proxy = new HttpProxyOptions();
}
