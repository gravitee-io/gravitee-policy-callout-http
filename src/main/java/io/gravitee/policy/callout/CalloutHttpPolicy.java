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
import io.gravitee.gateway.api.el.EvaluableRequest;
import io.gravitee.gateway.api.el.EvaluableResponse;
import io.gravitee.gateway.api.stream.BufferedReadWriteStream;
import io.gravitee.gateway.api.stream.ReadWriteStream;
import io.gravitee.gateway.api.stream.SimpleReadWriteStream;
import io.gravitee.policy.api.PolicyChain;
import io.gravitee.policy.api.PolicyResult;
import io.gravitee.policy.api.annotations.OnRequest;
import io.gravitee.policy.api.annotations.OnRequestContent;
import io.gravitee.policy.api.annotations.OnResponse;
import io.gravitee.policy.api.annotations.OnResponseContent;
import io.gravitee.policy.callout.configuration.CalloutHttpPolicyConfiguration;
import io.gravitee.policy.callout.configuration.PolicyScope;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.*;
import io.vertx.core.net.ProxyOptions;
import io.vertx.core.net.ProxyType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

import java.net.URI;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
public class CalloutHttpPolicy {

    private static final Logger LOGGER = LoggerFactory.getLogger(CalloutHttpPolicy.class);

    private static final String HTTPS_SCHEME = "https";

    private static final String CALLOUT_EXIT_ON_ERROR = "CALLOUT_EXIT_ON_ERROR";
    private static final String CALLOUT_HTTP_ERROR = "CALLOUT_HTTP_ERROR";

    /**
     * The associated configuration to this CalloutHttp Policy
     */
    private CalloutHttpPolicyConfiguration configuration;

    private final static String TEMPLATE_VARIABLE = "calloutResponse";

    private final static String REQUEST_TEMPLATE_VARIABLE = "request";
    private final static String RESPONSE_TEMPLATE_VARIABLE = "response";

    /**
     * Default values for timeouts
     */
    private final static Integer CONN_TIMEOUT = 1000;
    private final static Integer READ_TIMEOUT = 5000;

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

            initRequestResponseProperties(context);

            doCallout(context, __ -> policyChain.doNext(request, response), policyChain::failWith);
        } else {
            policyChain.doNext(request, response);
        }
    }

    @OnResponse
    public void onResponse(Request request, Response response, ExecutionContext context, PolicyChain policyChain) {
        if (configuration.getScope() == PolicyScope.RESPONSE) {

            initRequestResponseProperties(context);

            doCallout(context, __ -> policyChain.doNext(request, response), policyChain::failWith);
        } else {
            policyChain.doNext(request, response);
        }
    }

    private void initRequestResponseProperties(ExecutionContext context) {
        initRequestResponseProperties(context, null, null);
    }

    private void initRequestResponseProperties(ExecutionContext context, String requestContent, String responseContent) {
        context.getTemplateEngine().getTemplateContext()
                .setVariable(REQUEST_TEMPLATE_VARIABLE, new EvaluableRequest(context.request(), requestContent));

        context.getTemplateEngine().getTemplateContext()
                .setVariable(RESPONSE_TEMPLATE_VARIABLE, new EvaluableResponse(context.response(), responseContent));
    }

    @OnRequestContent
    public ReadWriteStream onRequestContent(ExecutionContext context, PolicyChain policyChain) {
        if (configuration.getScope() == PolicyScope.REQUEST_CONTENT) {
            return createStream(PolicyScope.REQUEST_CONTENT, context, policyChain);
        }

        return null;
    }

    @OnResponseContent
    public ReadWriteStream onResponseContent(ExecutionContext context, PolicyChain policyChain) {
        if (configuration.getScope() == PolicyScope.RESPONSE_CONTENT) {
            return createStream(PolicyScope.RESPONSE_CONTENT, context, policyChain);
        }

        return null;
    }

    private ReadWriteStream createStream(PolicyScope scope, ExecutionContext context, PolicyChain policyChain) {
        return new BufferedReadWriteStream() {

            io.gravitee.gateway.api.buffer.Buffer buffer = io.gravitee.gateway.api.buffer.Buffer.buffer();

            @Override
            public SimpleReadWriteStream<io.gravitee.gateway.api.buffer.Buffer> write(io.gravitee.gateway.api.buffer.Buffer content) {
                buffer.appendBuffer(content);
                return this;
            }

            @Override
            public void end() {
                initRequestResponseProperties(context,
                        (scope == PolicyScope.REQUEST_CONTENT) ? buffer.toString() : null,
                        (scope == PolicyScope.RESPONSE_CONTENT) ? buffer.toString() : null);

                doCallout(context, result -> {
                    if (buffer.length() > 0) {
                        super.write(buffer);
                    }

                    super.end();
                }, policyChain::streamFailWith);
            }
        };
    }

    private void doCallout(ExecutionContext context, Consumer<Void> onSuccess, Consumer<PolicyResult> onError) {
        Vertx vertx = context.getComponent(Vertx.class);

        try {
            String url = context.getTemplateEngine().convert(configuration.getUrl());
            TemplateEngine tplEngine = context.getTemplateEngine();
            Integer conTimeout = null;
            Integer readTimeout = null;
            URI target = URI.create(url);

            HttpClientOptions options = new HttpClientOptions();
            if (HTTPS_SCHEME.equalsIgnoreCase(target.getScheme())) {
                options.setSsl(true)
                        .setTrustAll(true)
                        .setVerifyHost(false);
            }

            if (configuration.getConTimeout() != null && !configuration.getConTimeout().isEmpty()) {
                conTimeout = tplEngine.getValue(configuration.getConTimeout(), Integer.class);
            } else {
                conTimeout = CONN_TIMEOUT;
            }

            if (configuration.getReadTimeout() != null && !configuration.getReadTimeout().isEmpty()) {
                readTimeout = tplEngine.getValue(configuration.getReadTimeout(), Integer.class);
            } else {
                readTimeout = READ_TIMEOUT;
            }

            options.setConnectTimeout(conTimeout);

            if (configuration.isUseSystemProxy()) {
                Environment env = context.getComponent(Environment.class);
                options.setProxyOptions(getSystemProxyOptions(env));
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

                                    // Put response into template variable for EL
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
                                        onSuccess.accept(null);
                                    } else {
                                        String errorContent = configuration.getErrorContent();
                                        try {
                                            errorContent = tplEngine.getValue(configuration.getErrorContent(), String.class);
                                        } catch (Exception ex) {
                                            // Do nothing
                                        }

                                        if (errorContent == null || errorContent.isEmpty()) {
                                            errorContent = "Request is terminated.";
                                        }

                                        onError.accept(PolicyResult
                                                .failure(CALLOUT_EXIT_ON_ERROR, configuration.getErrorStatusCode(), errorContent));
                                    }
                                }
                            });
                        }
                    }).exceptionHandler(throwable -> {

                        // exit and validate ExitOnError only if ignore exceptions are false
                        if (!configuration.isContinueOnTimeout()) {
                            // exit chain only if policy ask not ExitOnError
                            if (configuration.isExitOnError()) {
                                onError.accept(PolicyResult.failure(CALLOUT_HTTP_ERROR, throwable.getMessage()));
                            } else {
                                // otherwise continue chaining
                                onSuccess.accept(null);
                            }
                        } else {
                            // otherwise continue chaining
                            onSuccess.accept(null);
                        }

                        httpClient.close();
                    });

            httpRequest.setTimeout(readTimeout);

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
            onError.accept(PolicyResult.failure(CALLOUT_HTTP_ERROR, "Unable to apply expression language on the configured URL"));
        }
    }

    private ProxyOptions getSystemProxyOptions(Environment environment) {

        StringBuilder errors = new StringBuilder();
        ProxyOptions proxyOptions = new ProxyOptions();

        // System proxy must be well configured. Check that this is the case.
        if (environment.containsProperty("system.proxy.host")) {
            proxyOptions.setHost(environment.getProperty("system.proxy.host"));
        } else {
            errors.append("'system.proxy.host' ");
        }

        try {
            proxyOptions.setPort(Integer.parseInt(Objects.requireNonNull(environment.getProperty("system.proxy.port"))));
        } catch (Exception e) {
            errors.append("'system.proxy.port' [").append(environment.getProperty("system.proxy.port")).append("] ");
        }

        try {
            proxyOptions.setType(ProxyType.valueOf(environment.getProperty("system.proxy.type")));
        } catch (Exception e) {
            errors.append("'system.proxy.type' [").append(environment.getProperty("system.proxy.type")).append("] ");
        }

        proxyOptions.setUsername(environment.getProperty("system.proxy.username"));
        proxyOptions.setPassword(environment.getProperty("system.proxy.password"));

        if (errors.length() == 0) {
            return proxyOptions;
        } else {
            LOGGER.warn("CalloutHttp requires a system proxy to be defined but some configurations are missing or not well defined: {}. Ignoring proxy", errors);
            return null;
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
