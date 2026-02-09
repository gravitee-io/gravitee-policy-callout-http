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

import io.gravitee.el.TemplateEngine;
import io.gravitee.gateway.reactive.api.ExecutionFailure;
import io.gravitee.gateway.reactive.api.context.base.BaseExecutionContext;
import io.gravitee.gateway.reactive.api.context.http.HttpPlainExecutionContext;
import io.gravitee.gateway.reactive.api.context.kafka.KafkaMessageExecutionContext;
import io.gravitee.gateway.reactive.api.message.kafka.KafkaMessage;
import io.gravitee.gateway.reactive.api.policy.http.HttpPolicy;
import io.gravitee.gateway.reactive.api.policy.kafka.KafkaPolicy;
import io.gravitee.node.api.configuration.Configuration;
import io.gravitee.node.api.opentelemetry.Span;
import io.gravitee.node.api.opentelemetry.http.ObservableHttpClientRequest;
import io.gravitee.node.api.opentelemetry.http.ObservableHttpClientResponse;
import io.gravitee.node.vertx.proxy.VertxProxyOptionsUtils;
import io.gravitee.policy.callout.configuration.CalloutHttpPolicyConfiguration;
import io.gravitee.policy.callout.configuration.HttpHeader;
import io.gravitee.policy.v3.callout.CalloutHttpPolicyV3;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.RequestOptions;
import io.vertx.rxjava3.core.Vertx;
import io.vertx.rxjava3.core.buffer.Buffer;
import io.vertx.rxjava3.core.http.HttpClient;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
@Slf4j
public class CalloutHttpPolicy extends CalloutHttpPolicyV3 implements HttpPolicy, KafkaPolicy {

    private volatile HttpClient httpClient;

    public CalloutHttpPolicy(CalloutHttpPolicyConfiguration configuration) {
        super(configuration);
    }

    @Override
    public String id() {
        return "policy-http-callout";
    }

    @Override
    public Completable onRequest(HttpPlainExecutionContext ctx) {
        return Completable.defer(() -> doCallOut(ctx, ctx.getTemplateEngine()));
    }

    @Override
    public Completable onResponse(HttpPlainExecutionContext ctx) {
        return Completable.defer(() -> doCallOut(ctx, ctx.getTemplateEngine()));
    }

    @Override
    public Completable onMessageRequest(KafkaMessageExecutionContext ctx) {
        return ctx.request().onMessage(message -> doCallOut(ctx, message));
    }

    @Override
    public Completable onMessageResponse(KafkaMessageExecutionContext ctx) {
        return ctx.response().onMessage(message -> doCallOut(ctx, message));
    }

    private Maybe<KafkaMessage> doCallOut(KafkaMessageExecutionContext ctx, KafkaMessage message) {
        TemplateEngine templateEngine = ctx.getTemplateEngine(message);
        return doCallOut(ctx, templateEngine).andThen(Maybe.just(message));
    }

    private Completable doCallOut(BaseExecutionContext ctx, TemplateEngine templateEngine) {
        return CalloutUtils.prepareCalloutRequest(templateEngine, configuration).flatMapCompletable(reqConfig -> {
            if (configuration.isFireAndForget()) {
                return Completable.fromRunnable(() -> executeCallOut(ctx, reqConfig).onErrorComplete().subscribe());
            } else {
                return executeCallOut(ctx, reqConfig);
            }
        });
    }

    private Completable executeCallOut(BaseExecutionContext ctx, Req reqConfig) {
        var httpClient = getHttpClient(ctx);
        var requestOpts = new RequestOptions().setAbsoluteURI(reqConfig.url).setMethod(convert(configuration.getMethod()));
        ObservableHttpClientRequest observableHttpClientRequest = new ObservableHttpClientRequest(requestOpts);
        Span httpRequestSpan = ctx.getTracer().startSpanFrom(observableHttpClientRequest);
        return httpClient
            .rxRequest(requestOpts)
            .flatMap(req -> {
                observableHttpClientRequest.httpClientRequest(req.getDelegate());
                ctx.getTracer().injectSpanContext(req::putHeader);
                if (reqConfig.headerList() != null) {
                    reqConfig
                        .headerList()
                        .stream()
                        .filter(header -> header.getValue() != null)
                        .forEach(header -> req.putHeader(header.getName(), header.getValue()));
                }

                if (reqConfig.body().isPresent() && !reqConfig.body().get().isEmpty()) {
                    req.headers().remove(HttpHeaders.TRANSFER_ENCODING);
                    // Removing Content-Length header to let VertX automatically set it correctly
                    req.headers().remove(HttpHeaders.CONTENT_LENGTH);
                    return req.rxSend(Buffer.buffer(reqConfig.body().get()));
                }

                return req.send();
            })
            .onErrorResumeNext(throwable -> Single.error(new CalloutException(throwable)))
            .flatMap(httpClientResponse ->
                httpClientResponse
                    .body()
                    .map(responseBody -> new CalloutResponse(httpClientResponse.getDelegate(), responseBody.toString()))
                    .map(calloutResponse -> new CalloutResponseWithDelegate(calloutResponse, httpClientResponse.getDelegate()))
            )
            .flatMapCompletable(calloutResponseWithDelegate -> processCalloutResponse(ctx, calloutResponseWithDelegate, httpRequestSpan))
            .onErrorResumeNext(th -> {
                ctx.getTracer().endOnError(httpRequestSpan, th);

                if (th instanceof CalloutException && configuration.isExitOnError()) {
                    log.error(th.getCause().getMessage(), th.getCause());
                    if (ctx instanceof HttpPlainExecutionContext httpContext) {
                        return httpContext.interruptWith(
                            new ExecutionFailure(configuration.getErrorStatusCode())
                                .key(CALLOUT_HTTP_ERROR)
                                .message(th.getCause().getMessage())
                        );
                    } else if (ctx instanceof KafkaMessageExecutionContext kafkaContext) {
                        return kafkaContext.executionContext().interruptWith(org.apache.kafka.common.protocol.Errors.UNKNOWN_SERVER_ERROR);
                    }
                }
                return Completable.error(th);
            });
    }

    private Completable processCalloutResponse(
        BaseExecutionContext ctx,
        CalloutResponseWithDelegate calloutResponseWithDelegate,
        Span httpRequestSpan
    ) {
        CalloutResponse calloutResponse = calloutResponseWithDelegate.calloutResponse();
        HttpClientResponse httpClientResponse = calloutResponseWithDelegate.httpClientResponse();

        // Create observable response for tracing
        ObservableHttpClientResponse observableHttpClientResponse = new ObservableHttpClientResponse(httpClientResponse);

        if (configuration.isFireAndForget()) {
            ctx.getTracer().endWithResponse(httpRequestSpan, observableHttpClientResponse);
            return Completable.complete();
        }

        // Variables and exit on error are only managed if the fire & forget is disabled.
        ctx.getTemplateEngine().getTemplateContext().setVariable(TEMPLATE_VARIABLE, calloutResponse);

        if (configuration.isExitOnError()) {
            return ctx
                .getTemplateEngine()
                .eval(configuration.getErrorCondition(), Boolean.class)
                .flatMapCompletable(exit -> {
                    if (!exit) {
                        ctx.getTracer().endWithResponse(httpRequestSpan, observableHttpClientResponse);
                        return processSuccess(ctx);
                    }
                    httpRequestSpan.withAttribute(
                        "error.condition.evaluation.message",
                        "Callout failed due to error condition evaluation: " + configuration.getErrorCondition()
                    );
                    return processError(ctx);
                });
        }
        ctx.getTracer().endWithResponse(httpRequestSpan, observableHttpClientResponse);
        return processSuccess(ctx);
    }

    private Completable processSuccess(BaseExecutionContext ctx) {
        return Flowable.fromIterable(configuration.getVariables())
            .flatMapCompletable(variable -> {
                ctx.setAttribute(variable.getName(), null);
                return Maybe.just(variable)
                    .flatMap(var -> {
                        Class<?> clazz = var.isEvaluateAsString() ? String.class : Object.class;
                        return ctx.getTemplateEngine().eval(var.getValue(), clazz);
                    })
                    .doOnSuccess(value -> ctx.setAttribute(variable.getName(), value))
                    .ignoreElement();
            })
            .doOnComplete(() -> ctx.getTemplateEngine().getTemplateContext().setVariable(TEMPLATE_VARIABLE, null));
    }

    private Completable processError(BaseExecutionContext ctx) {
        return Maybe.fromSupplier(configuration::getErrorContent)
            .flatMap(content -> ctx.getTemplateEngine().eval(content, String.class))
            .switchIfEmpty(Single.just("Request is terminated."))
            .flatMapCompletable(errorContent -> {
                if (ctx instanceof HttpPlainExecutionContext httpContext) {
                    return httpContext.interruptWith(
                        new ExecutionFailure(configuration.getErrorStatusCode()).key(CALLOUT_EXIT_ON_ERROR).message(errorContent)
                    );
                } else if (ctx instanceof KafkaMessageExecutionContext kafkaContext) {
                    log.warn("Callout policy is interrupting Kafka message processing. Error content: {}", errorContent);
                    return kafkaContext.executionContext().interruptWith(org.apache.kafka.common.protocol.Errors.UNKNOWN_SERVER_ERROR);
                }
                return Completable.error(new IllegalStateException("Unsupported execution context : " + ctx.getClass().getName()));
            });
    }

    public record Req(String url, Optional<String> body, List<HttpHeader> headerList) {}

    /**
     *
     * @param ctx The context to get Vertx component
     * @return Built or existing HttpClient
     */
    private HttpClient getHttpClient(BaseExecutionContext ctx) {
        if (this.httpClient == null) {
            // Vertx only uses ssl options when https
            var options = new HttpClientOptions().setSsl(true).setTrustAll(true).setVerifyHost(false);

            if (configuration.isUseSystemProxy()) {
                Configuration config = ctx.getComponent(Configuration.class);
                try {
                    options.setProxyOptions(VertxProxyOptionsUtils.buildProxyOptions(config));
                } catch (IllegalStateException e) {
                    log.warn(
                        "CalloutHttp requires a system proxy to be defined but some configurations are missing or not well defined: {}. Ignoring proxy",
                        e.getMessage()
                    );
                }
            }
            var vertx = ctx.getComponent(Vertx.class);
            this.httpClient = vertx.createHttpClient(options);
        }
        return this.httpClient;
    }
}
