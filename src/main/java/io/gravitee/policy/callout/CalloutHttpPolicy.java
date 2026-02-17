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
import io.gravitee.node.vertx.client.http.VertxHttpClientFactory;
import io.gravitee.plugin.mappers.HttpClientOptionsMapper;
import io.gravitee.plugin.mappers.HttpProxyOptionsMapper;
import io.gravitee.plugin.mappers.SslOptionsMapper;
import io.gravitee.policy.callout.configuration.CalloutHttpPolicyConfiguration;
import io.gravitee.policy.callout.configuration.HttpHeader;
import io.gravitee.policy.v3.callout.CalloutHttpPolicyV3;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.Single;
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
        var httpClient = getHttpClient(ctx, reqConfig.url());
        var requestOpts = new RequestOptions().setAbsoluteURI(reqConfig.url()).setMethod(convert(configuration.getMethod()));
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
                log.error("Callout policy failed with error", th);
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
        io.vertx.core.http.HttpClientResponse httpClientResponse = calloutResponseWithDelegate.httpClientResponse();

        // Create observable response for tracing
        ObservableHttpClientResponse observableHttpClientResponse = new ObservableHttpClientResponse(httpClientResponse);

        if (configuration.isFireAndForget()) {
            ctx.getTracer().endWithResponse(httpRequestSpan, observableHttpClientResponse);
            return Completable.complete();
        }

        // Variables and exit on error are only managed if the fire & forget is
        // disabled.
        TemplateEngine templateEngine = ctx.getTemplateEngine();
        templateEngine.getTemplateContext().setVariable("calloutResponse", calloutResponse);
        ctx.setAttribute("calloutResponse", calloutResponse);

        return evaluateErrorCondition(ctx, templateEngine, calloutResponse, observableHttpClientResponse, httpRequestSpan)
            .andThen(processVariables(ctx, templateEngine))
            .doOnTerminate(() -> ctx.getTracer().endWithResponse(httpRequestSpan, observableHttpClientResponse));
    }

    private Completable evaluateErrorCondition(
        BaseExecutionContext ctx,
        TemplateEngine templateEngine,
        CalloutResponse calloutResponse,
        ObservableHttpClientResponse observableHttpClientResponse,
        Span httpRequestSpan
    ) {
        if (configuration.isExitOnError() && configuration.getErrorCondition() != null && !configuration.getErrorCondition().isEmpty()) {
            return templateEngine
                .eval(configuration.getErrorCondition(), Boolean.class)
                .switchIfEmpty(Maybe.fromCallable(() -> Boolean.valueOf(configuration.getErrorCondition())))
                .flatMapCompletable(exit -> {
                    if (exit) {
                        httpRequestSpan.withAttribute(
                            "error.condition.evaluation.message",
                            "Callout failed due to error condition evaluation: " + configuration.getErrorCondition()
                        );
                        return processErrorInterruption(ctx, templateEngine);
                    }
                    return Completable.complete();
                });
        }
        return Completable.complete();
    }

    private Completable processVariables(BaseExecutionContext ctx, TemplateEngine templateEngine) {
        if (configuration.getVariables() != null && !configuration.getVariables().isEmpty()) {
            return Observable.fromIterable(configuration.getVariables()).flatMapCompletable(variable -> {
                Maybe<Object> evalMaybe;
                if (variable.isEvaluateAsString()) {
                    evalMaybe = templateEngine.eval(variable.getValue(), String.class).cast(Object.class);
                } else {
                    evalMaybe = templateEngine.eval(variable.getValue(), Object.class);
                }

                return evalMaybe
                    .switchIfEmpty(Maybe.fromCallable(variable::getValue))
                    .flatMapCompletable(value -> {
                        log.debug("Setting attribute {}: {}", variable.getName(), value);
                        ctx.setAttribute(variable.getName(), value);
                        return Completable.complete();
                    });
            });
        }
        return Completable.complete();
    }

    private Completable processErrorInterruption(BaseExecutionContext ctx, TemplateEngine templateEngine) {
        return Maybe.fromSupplier(configuration::getErrorContent)
            .flatMap(content -> templateEngine.eval(content, String.class))
            .switchIfEmpty(
                Maybe.fromCallable(() ->
                    configuration.getErrorContent() != null ? configuration.getErrorContent() : "Request is terminated."
                )
            )
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
    private HttpClient getHttpClient(BaseExecutionContext ctx, String url) {
        io.vertx.rxjava3.core.Vertx vertx = ctx.getComponent(io.vertx.rxjava3.core.Vertx.class);
        if (vertx == null) {
            io.vertx.core.Vertx coreVertx = ctx.getComponent(io.vertx.core.Vertx.class);
            if (coreVertx != null) {
                vertx = io.vertx.rxjava3.core.Vertx.newInstance(coreVertx);
            }
        }

        return VertxHttpClientFactory.builder()
            .vertx(vertx)
            .nodeConfiguration(ctx.getComponent(Configuration.class))
            .defaultTarget(url)
            .httpOptions(HttpClientOptionsMapper.INSTANCE.map(configuration.getHttpClientOptions()))
            .sslOptions(SslOptionsMapper.INSTANCE.map(configuration.getSslOptions()))
            .proxyOptions(HttpProxyOptionsMapper.INSTANCE.map(configuration.getHttpProxyOptions()))
            .build()
            .createHttpClient();
    }
}
