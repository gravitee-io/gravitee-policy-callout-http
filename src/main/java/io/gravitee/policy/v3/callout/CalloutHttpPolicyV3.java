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

import io.gravitee.el.TemplateEngine;
import io.gravitee.gateway.api.ExecutionContext;
import io.gravitee.gateway.api.Request;
import io.gravitee.gateway.api.Response;
import io.gravitee.gateway.api.el.EvaluableRequest;
import io.gravitee.gateway.api.el.EvaluableResponse;
import io.gravitee.gateway.api.stream.BufferedReadWriteStream;
import io.gravitee.gateway.api.stream.ReadWriteStream;
import io.gravitee.gateway.api.stream.SimpleReadWriteStream;
import io.gravitee.node.api.configuration.Configuration;
import io.gravitee.node.vertx.client.http.VertxHttpClientFactory;
import io.gravitee.plugin.mappers.HttpClientOptionsMapper;
import io.gravitee.plugin.mappers.HttpProxyOptionsMapper;
import io.gravitee.plugin.mappers.SslOptionsMapper;
import io.gravitee.policy.api.PolicyChain;
import io.gravitee.policy.api.PolicyResult;
import io.gravitee.policy.api.annotations.OnRequest;
import io.gravitee.policy.api.annotations.OnRequestContent;
import io.gravitee.policy.api.annotations.OnResponse;
import io.gravitee.policy.api.annotations.OnResponseContent;
import io.gravitee.policy.callout.CalloutResponse;
import io.gravitee.policy.callout.configuration.CalloutHttpPolicyConfiguration;
import io.gravitee.policy.callout.configuration.PolicyScope;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import java.net.URI;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
public class CalloutHttpPolicyV3 {

    private static final Logger LOGGER = LoggerFactory.getLogger(CalloutHttpPolicyV3.class);

    protected static final String HTTPS_SCHEME = "https";

    public static final String CALLOUT_EXIT_ON_ERROR = "CALLOUT_EXIT_ON_ERROR";
    public static final String CALLOUT_HTTP_ERROR = "CALLOUT_HTTP_ERROR";

    protected static final String TEMPLATE_VARIABLE = "calloutResponse";

    private static final String REQUEST_TEMPLATE_VARIABLE = "request";
    private static final String RESPONSE_TEMPLATE_VARIABLE = "response";

    /**
     * The associated configuration to this CalloutHttp Policy
     */
    protected final CalloutHttpPolicyConfiguration configuration;

    private volatile HttpClient httpClient;

    /**
     * Create a new CalloutHttp Policy instance based on its associated configuration
     *
     * @param configuration the associated configuration to the new CalloutHttp Policy instance
     */
    public CalloutHttpPolicyV3(CalloutHttpPolicyConfiguration configuration) {
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
        context
            .getTemplateEngine()
            .getTemplateContext()
            .setVariable(REQUEST_TEMPLATE_VARIABLE, new EvaluableRequest(context.request(), requestContent));

        context
            .getTemplateEngine()
            .getTemplateContext()
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
                initRequestResponseProperties(
                    context,
                    (scope == PolicyScope.REQUEST_CONTENT) ? buffer.toString() : null,
                    (scope == PolicyScope.RESPONSE_CONTENT) ? buffer.toString() : null
                );

                doCallout(
                    context,
                    result -> {
                        if (buffer.length() > 0) {
                            super.write(buffer);
                        }

                        super.end();
                    },
                    policyChain::streamFailWith
                );
            }
        };
    }

    private void doCallout(ExecutionContext context, Consumer<Void> onSuccess, Consumer<PolicyResult> onError) {
        final Consumer<Void> onSuccessCallback;
        final Consumer<PolicyResult> onErrorCallback;

        if (configuration.isFireAndForget()) {
            // If fire & forget, continue the chaining before making the http callout.
            onSuccess.accept(null);

            // callBacks need to be replaced because the fire & forget mode does not allow to act on the request / response once the http call as been performed.
            onSuccessCallback = aVoid -> {};
            onErrorCallback = policyResult -> {};
        } else {
            // Preserve original callback when not in fire & forget mode.
            onSuccessCallback = onSuccess;
            onErrorCallback = onError;
        }

        try {
            String url = context.getTemplateEngine().evalNow(configuration.getUrl(), String.class);

            HttpClient httpClient = getOrBuildHttpClient(context);

            RequestOptions requestOpts = new RequestOptions().setAbsoluteURI(url).setMethod(convert(configuration.getMethod()));

            final Future<HttpClientRequest> futureRequest = httpClient.request(requestOpts);

            futureRequest.onFailure(throwable -> handleFailure(onSuccessCallback, onErrorCallback, throwable));

            futureRequest.onSuccess(httpClientRequest -> {
                // Connection is made, lets continue.
                final Future<HttpClientResponse> futureResponse;

                if (configuration.getHeaders() != null) {
                    configuration
                        .getHeaders()
                        .forEach(header -> {
                            try {
                                String extValue = (header.getValue() != null)
                                    ? context.getTemplateEngine().evalNow(header.getValue(), String.class)
                                    : null;
                                if (extValue != null) {
                                    httpClientRequest.putHeader(header.getName(), extValue);
                                }
                            } catch (Exception ex) {
                                LOGGER.warn("Could not set header [{}]: {}", header.getName(), ex.getMessage());
                            }
                        });
                }

                String body = null;

                if (configuration.getBody() != null && !configuration.getBody().isEmpty()) {
                    // Body can be dynamically resolved using el expression.
                    body = context.getTemplateEngine().evalNow(configuration.getBody(), String.class);
                }

                // Check the resolved body before trying to send it.
                if (body != null && !body.isEmpty()) {
                    httpClientRequest.headers().remove(HttpHeaders.TRANSFER_ENCODING);
                    // Removing Content-Length header to let VertX automatically set it correctly
                    httpClientRequest.headers().remove(HttpHeaders.CONTENT_LENGTH);
                    futureResponse = httpClientRequest.send(Buffer.buffer(body));
                } else {
                    futureResponse = httpClientRequest.send();
                }

                futureResponse
                    .onSuccess(httpResponse -> handleSuccess(context, onSuccessCallback, onErrorCallback, httpResponse))
                    .onFailure(throwable -> handleFailure(onSuccessCallback, onErrorCallback, throwable));
            });
        } catch (Exception ex) {
            onErrorCallback.accept(PolicyResult.failure(CALLOUT_HTTP_ERROR, "Unable to apply expression language on the configured URL"));
        }
    }

    private void handleSuccess(
        ExecutionContext context,
        Consumer<Void> onSuccess,
        Consumer<PolicyResult> onError,
        HttpClientResponse httpResponse
    ) {
        httpResponse.bodyHandler(body -> {
            TemplateEngine tplEngine = context.getTemplateEngine();

            // Put response into template variable for EL
            final CalloutResponse calloutResponse = new CalloutResponse(httpResponse, body.toString());

            if (!configuration.isFireAndForget()) {
                // Variables and exit on error are only managed if the fire & forget is disabled.
                tplEngine.getTemplateContext().setVariable(TEMPLATE_VARIABLE, calloutResponse);

                // Process callout response
                boolean exit = false;

                if (configuration.isExitOnError()) {
                    exit = tplEngine.evalNow(configuration.getErrorCondition(), Boolean.class);
                }

                if (!exit) {
                    // Set context variables
                    if (configuration.getVariables() != null) {
                        configuration
                            .getVariables()
                            .forEach(variable -> {
                                try {
                                    if (variable.getValue() != null) {
                                        Class<?> clazz = variable.isEvaluateAsString() ? String.class : Object.class;
                                        context.setAttribute(variable.getName(), tplEngine.evalNow(variable.getValue(), clazz));
                                    } else {
                                        context.setAttribute(variable.getName(), null);
                                    }
                                } catch (Throwable ex) {
                                    LOGGER.warn("Could not set variable [{}]: {}", variable.getName(), ex.getMessage());
                                }
                            });
                    }

                    tplEngine.getTemplateContext().setVariable(TEMPLATE_VARIABLE, null);

                    // Finally continue chaining
                    onSuccess.accept(null);
                } else {
                    String errorContent = configuration.getErrorContent();
                    try {
                        errorContent = tplEngine.evalNow(configuration.getErrorContent(), String.class);
                    } catch (Exception ex) {
                        LOGGER.warn("Could not evaluate error content: {}", ex.getMessage());
                    }

                    if (errorContent == null || errorContent.isEmpty()) {
                        errorContent = "Request is terminated.";
                    }

                    onError.accept(PolicyResult.failure(CALLOUT_EXIT_ON_ERROR, configuration.getErrorStatusCode(), errorContent));
                }
            }
        });
    }

    private void handleFailure(Consumer<Void> onSuccess, Consumer<PolicyResult> onError, Throwable throwable) {
        if (configuration.isExitOnError()) {
            // exit chain only if policy ask ExitOnError
            onError.accept(PolicyResult.failure(CALLOUT_HTTP_ERROR, throwable.getMessage()));
        } else {
            // otherwise continue chaining
            onSuccess.accept(null);
        }
    }

    protected HttpMethod convert(io.gravitee.common.http.HttpMethod httpMethod) {
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
                return HttpMethod.valueOf("OTHER");
        }

        return null;
    }

    private HttpClient getOrBuildHttpClient(ExecutionContext ctx) {
        if (httpClient == null) {
            synchronized (this) {
                if (httpClient == null) {
                    String defaultUrl = ctx.getTemplateEngine().evalNow(configuration.getUrl(), String.class);

                    // 1. Get Core Vertx from context (V3 API)
                    Vertx coreVertx = ctx.getComponent(Vertx.class);

                    // 2. Wrap it into RxJava3 Vertx because VertxHttpClientFactory requires it
                    io.vertx.rxjava3.core.Vertx rxVertx = io.vertx.rxjava3.core.Vertx.newInstance(coreVertx);

                    // 3. Use the Factory to build the client (returns RxJava3 HttpClient)
                    // 4. Unwrap it (.getDelegate()) back to Core HttpClient
                    httpClient = VertxHttpClientFactory.builder()
                        .vertx(rxVertx)
                        .nodeConfiguration(ctx.getComponent(Configuration.class))
                        .defaultTarget(defaultUrl)
                        .httpOptions(HttpClientOptionsMapper.INSTANCE.map(configuration.getHttp()))
                        .proxyOptions(HttpProxyOptionsMapper.INSTANCE.map(configuration.getProxy()))
                        .sslOptions(SslOptionsMapper.INSTANCE.map(configuration.getSsl()))
                        .build()
                        .createHttpClient()
                        .getDelegate();
                }
            }
        }
        return httpClient;
    }
}
