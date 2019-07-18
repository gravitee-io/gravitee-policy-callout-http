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
package io.gravitee.policy.callout;

import io.gravitee.el.TemplateEngine;
import io.gravitee.gateway.api.ExecutionContext;
import io.gravitee.gateway.api.Request;
import io.gravitee.gateway.api.Response;
import io.gravitee.policy.api.PolicyChain;
import io.gravitee.policy.api.PolicyResult;
import io.gravitee.policy.api.annotations.OnRequest;
import io.gravitee.policy.api.annotations.OnResponse;
import io.gravitee.policy.callout.configuration.CalloutHttpPolicyConfiguration;
import io.gravitee.policy.callout.configuration.PolicyScope;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.*;

import java.net.URI;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
public class CalloutHttpPolicy {

    private static final String HTTPS_SCHEME = "https";

    /**
     * The associated configuration to this CalloutHttp Policy
     */
    private CalloutHttpPolicyConfiguration configuration;

    private final static String TEMPLATE_VARIABLE = "calloutResponse";

    /**
     * Create a new CalloutHttp Policy instance based on its associated configuration
     *
     * @param configuration the associated configuration to the new CalloutHttp Policy instance
     */
    public CalloutHttpPolicy(CalloutHttpPolicyConfiguration configuration) {
        this.configuration = configuration;
    }

    @OnRequest
    public void onRequest(Request request, Response response, ExecutionContext context, PolicyChain policyChain) {
        if (configuration.getScope() == null || configuration.getScope() == PolicyScope.REQUEST) {
            doCallout(request, response, context, policyChain);
        } else {
            policyChain.doNext(request, response);
        }
    }

    @OnResponse
    public void onResponse(Request request, Response response, ExecutionContext context, PolicyChain policyChain) {
        if (configuration.getScope() == PolicyScope.RESPONSE) {
            doCallout(request, response, context, policyChain);
        } else {
            policyChain.doNext(request, response);
        }
    }

    private void doCallout(Request request, Response response, ExecutionContext context, PolicyChain policyChain) {
        Vertx vertx = context.getComponent(Vertx.class);

        try {
            String url = context.getTemplateEngine().convert(configuration.getUrl());
            URI target = URI.create(url);

            HttpClientOptions options = new HttpClientOptions();
            if (HTTPS_SCHEME.equalsIgnoreCase(target.getScheme())) {
                options.setSsl(true).setTrustAll(true);
            }

            HttpClient httpClient = vertx.createHttpClient(options);

            HttpClientRequest httpRequest = httpClient
                    .requestAbs(convert(configuration.getMethod()), url)
                    .handler(new Handler<HttpClientResponse>() {
                        @Override
                        public void handle(HttpClientResponse httpResponse) {
                            httpResponse.bodyHandler(new Handler<Buffer>() {
                                @Override
                                public void handle(Buffer body) {
                                    TemplateEngine tplEngine = context.getTemplateEngine();

                                    // Put response ino template variable for EL
                                    tplEngine.getTemplateContext()
                                            .setVariable(TEMPLATE_VARIABLE, new CalloutResponse(httpResponse, body.toString()));

                                    // Close HTTP client
                                    httpClient.close();

                                    // Process callout response
                                    boolean exit = false;

                                    if (configuration.isExitOnError()) {
                                        exit = tplEngine.getValue(configuration.getErrorCondition(), Boolean.class);
                                    }

                                    if (!exit) {
                                        // Set context variables
                                        if (configuration.getVariables() != null) {
                                            configuration.getVariables().forEach(variable -> {
                                                try {
                                                    String extValue = (variable.getValue() != null) ?
                                                            tplEngine.getValue(variable.getValue(), String.class) : null;

                                                    context.setAttribute(variable.getName(), extValue);
                                                } catch (Exception ex) {
                                                    // Do nothing
                                                }
                                            });
                                        }

                                        tplEngine.getTemplateContext()
                                                .setVariable(TEMPLATE_VARIABLE, null);

                                        // Finally continue chaining
                                        policyChain.doNext(request, response);
                                    } else {
                                        String errorContent = configuration.getErrorContent();

                                        if (errorContent == null || errorContent.isEmpty()) {
                                            errorContent = "Request is terminated.";
                                        }

                                        policyChain.failWith(
                                                PolicyResult
                                                        .failure(configuration.getErrorStatusCode(), errorContent));
                                    }
                                }
                            });
                        }
                    }).exceptionHandler(throwable -> {
                        // Finally exit chain
                        policyChain.failWith(PolicyResult.failure(throwable.getMessage()));

                        httpClient.close();
                    });

            if (configuration.getHeaders() != null) {
                configuration.getHeaders().forEach(header -> {
                    try {
                        String extValue = (header.getValue() != null) ?
                                context.getTemplateEngine().convert(header.getValue()) : null;
                        if (extValue != null) {
                            httpRequest.putHeader(header.getName(), extValue);
                        }
                    } catch (Exception ex) {
                        // Do nothing
                    }
                });
            }

            if (configuration.getBody() != null && !configuration.getBody().isEmpty()) {
                String body = context.getTemplateEngine()
                        .getValue(configuration.getBody(), String.class);
                httpRequest.headers().remove(HttpHeaders.TRANSFER_ENCODING);
                httpRequest.putHeader(HttpHeaders.CONTENT_LENGTH, Integer.toString(body.length()));
                httpRequest.end(Buffer.buffer(body));
            } else {
                httpRequest.end();
            }
        } catch (Exception ex) {
            policyChain.failWith(PolicyResult.failure("Unable to apply expression language on the configured URL"));
        }
    }

    private HttpMethod convert(io.gravitee.common.http.HttpMethod httpMethod) {
        switch (httpMethod) {
            case CONNECT:
                return HttpMethod.CONNECT;
            case DELETE:
                return HttpMethod.DELETE;
            case GET:
                return HttpMethod.GET;
            case HEAD:
                return HttpMethod.HEAD;
            case OPTIONS:
                return HttpMethod.OPTIONS;
            case PATCH:
                return HttpMethod.PATCH;
            case POST:
                return HttpMethod.POST;
            case PUT:
                return HttpMethod.PUT;
            case TRACE:
                return HttpMethod.TRACE;
            case OTHER:
                return HttpMethod.OTHER;
        }

        return null;
    }
}
