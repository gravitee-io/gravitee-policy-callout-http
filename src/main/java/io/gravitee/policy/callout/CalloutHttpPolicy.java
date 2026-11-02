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
import io.reactivex.rxjava3.exceptions.CompositeException;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.PoolOptions;
import io.vertx.core.http.RequestOptions;
import io.vertx.rxjava3.core.Vertx;
import io.vertx.rxjava3.core.http.HttpClient;
import io.vertx.rxjava3.core.http.HttpClientRequest;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import lombok.CustomLog;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
@CustomLog
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
        return CalloutUtils.prepareCalloutRequest(templateEngine, configuration)
            .onErrorResumeNext(th -> Single.error(new CalloutException(th)))
            .flatMapCompletable(reqConfig -> {
                if (configuration.isFireAndForget()) {
                    return Completable.fromRunnable(() ->
                        executeCallOut(ctx, reqConfig)
                            .onErrorResumeNext(th -> handleCalloutFailure(ctx, th))
                            .onErrorComplete()
                            .subscribe()
                    );
                } else {
                    return executeCallOut(ctx, reqConfig);
                }
            })
            .onErrorResumeNext(th -> handleCalloutFailure(ctx, th));
    }

    private Completable executeCallOut(BaseExecutionContext ctx, Req reqConfig) {
        return Single.fromCallable(() -> new RequestOptions().setAbsoluteURI(reqConfig.url).setMethod(convert(configuration.getMethod())))
            .onErrorResumeNext(throwable -> Single.error(new CalloutException(throwable)))
            .flatMapCompletable(requestOpts -> {
                var httpClient = getHttpClient(ctx);
                ObservableHttpClientRequest observableHttpClientRequest = new ObservableHttpClientRequest(requestOpts);
                Span httpRequestSpan = ctx.getTracer().startSpanFrom(observableHttpClientRequest);
                // Tracks the request between pool acquisition and the response so that disposing the chain — typically
                // the gateway cancelling the enclosing request on its timeout — resets it and releases the pooled
                // connection at once, instead of leaking its slot or holding a dead connection for ever.
                AtomicReference<HttpClientRequest> pendingRequest = new AtomicReference<>();
                return acquireCalloutRequest(httpClient, requestOpts, pendingRequest)
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
                    .flatMap(httpClientResponse ->
                        httpClientResponse
                            .body()
                            .map(responseBody -> new CalloutResponse(httpClientResponse.getDelegate(), responseBody.toString()))
                            .map(calloutResponse -> new CalloutResponseWithDelegate(calloutResponse, httpClientResponse.getDelegate()))
                    )
                    .onErrorResumeNext(throwable -> Single.error(new CalloutException(throwable)))
                    .flatMapCompletable(calloutResponseWithDelegate ->
                        processCalloutResponse(ctx, calloutResponseWithDelegate, httpRequestSpan)
                    )
                    .doOnError(th -> ctx.getTracer().endOnError(httpRequestSpan, th))
                    .doOnDispose(() -> resetPendingCalloutRequest(pendingRequest));
            });
    }

    /**
     * Acquires a request from the client's connection pool, resetting it immediately when the chain has already been
     * disposed by the time the pool grants a connection. The rx bridge cannot do this by itself: disposing the
     * {@code Single} returned by {@code rxRequest} only drops the observer, so a connection granted afterwards would
     * silently keep its pool slot with no request ever written on it — under sustained cancel/retry load this keeps
     * the pool collapsed long after the callout target has recovered. Mirrors the v4 HTTP proxy
     * endpoint's {@code acquireUpstreamRequest}.
     */
    private Single<HttpClientRequest> acquireCalloutRequest(
        HttpClient httpClient,
        RequestOptions requestOpts,
        AtomicReference<HttpClientRequest> pendingRequest
    ) {
        return Single.create(emitter ->
            httpClient
                .getDelegate()
                .request(requestOpts)
                .onComplete(asyncRequest -> {
                    if (asyncRequest.succeeded()) {
                        HttpClientRequest request = HttpClientRequest.newInstance(asyncRequest.result());
                        // Publish before the disposed check so a concurrent disposal either sees it (doOnDispose
                        // resets it) or is caught right here; getAndSet(null) in the reset makes a double reset a no-op.
                        pendingRequest.set(request);
                        if (emitter.isDisposed()) {
                            resetPendingCalloutRequest(pendingRequest);
                        } else {
                            emitter.onSuccess(request);
                        }
                    } else {
                        emitter.tryOnError(asyncRequest.cause());
                    }
                })
        );
    }

    /**
     * Resets the pending callout request, if any, releasing its pooled connection right away rather than leaving it
     * held until something else closes it. {@code reset()} is a no-op on a request that already completed, and
     * {@code getAndSet(null)} makes a second reset (dispose racing the post-acquisition disposed check) a no-op too.
     */
    private void resetPendingCalloutRequest(AtomicReference<HttpClientRequest> pendingRequest) {
        HttpClientRequest request = pendingRequest.getAndSet(null);
        if (request != null) {
            try {
                request.reset();
            } catch (Exception e) {
                log.warn("Can't properly reset callout request after downstream disposal; its pool connection may stay reserved", e);
            }
        }
    }

    private Completable handleCalloutFailure(BaseExecutionContext ctx, Throwable throwable) {
        CalloutException calloutException = unwrapCalloutException(throwable);
        if (calloutException == null) {
            return Completable.error(throwable);
        }

        Throwable cause = calloutException.getCause();
        String reason = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
        boolean tolerated = configuration.isFireAndForget() || !configuration.isExitOnError();
        if (tolerated) {
            // The failure is tolerated: the policy chain continues, so this isn't an operator-facing error.
            ctx.withLogger(log).warn("HTTP Callout to '{}' failed, continuing: {}", configuration.getUrl(), reason, cause);
            return Completable.complete();
        }

        ctx.withLogger(log).error("HTTP Callout to '{}' failed: {}", configuration.getUrl(), reason, cause);

        if (ctx instanceof HttpPlainExecutionContext httpContext) {
            return httpContext.interruptWith(
                new ExecutionFailure(configuration.getErrorStatusCode())
                    .key(CALLOUT_HTTP_ERROR)
                    // The underlying cause (target URL, EL expression, internal hostnames, ...) is kept in the
                    // cause and the logs only: surfacing it here would leak the callout configuration to the caller.
                    .message("The HTTP callout could not be completed.")
                    .cause(calloutException)
            );
        } else if (ctx instanceof KafkaMessageExecutionContext kafkaContext) {
            return kafkaContext.executionContext().interruptWith(org.apache.kafka.common.protocol.Errors.UNKNOWN_SERVER_ERROR);
        }

        return Completable.error(calloutException);
    }

    /**
     * Unwraps a {@link CalloutException} from the given throwable, including when RxJava has combined it into a
     * {@link CompositeException} (e.g. when a {@code doOnError} handler such as the tracer's {@code endOnError}
     * itself throws while processing the original failure).
     */
    private CalloutException unwrapCalloutException(Throwable throwable) {
        if (throwable instanceof CalloutException calloutException) {
            return calloutException;
        }
        if (throwable instanceof CompositeException compositeException) {
            for (Throwable nested : compositeException.getExceptions()) {
                CalloutException found = unwrapCalloutException(nested);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
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
                .onErrorResumeNext(throwable -> Maybe.error(new CalloutException(throwable)))
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
            .doOnComplete(() -> ctx.getTemplateEngine().getTemplateContext().setVariable(TEMPLATE_VARIABLE, null))
            .onErrorResumeNext(throwable -> Completable.error(new CalloutException(throwable)));
    }

    private Completable processError(BaseExecutionContext ctx) {
        return Maybe.fromSupplier(configuration::getErrorContent)
            .flatMap(content -> ctx.getTemplateEngine().eval(content, String.class))
            .onErrorResumeNext(throwable -> Maybe.error(new CalloutException(throwable)))
            .switchIfEmpty(Single.just("Request is terminated."))
            .flatMapCompletable(errorContent -> {
                if (ctx instanceof HttpPlainExecutionContext httpContext) {
                    return httpContext.interruptWith(
                        new ExecutionFailure(configuration.getErrorStatusCode()).key(CALLOUT_EXIT_ON_ERROR).message(errorContent)
                    );
                } else if (ctx instanceof KafkaMessageExecutionContext kafkaContext) {
                    ctx.withLogger(log).warn("Callout policy is interrupting Kafka message processing. Error content: {}", errorContent);
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
    HttpClient getHttpClient(BaseExecutionContext ctx) {
        if (this.httpClient == null) {
            synchronized (this) {
                if (this.httpClient == null) {
                    // SSL options (trustAll, verifyHost) are set at client level but only applied per-request
                    // when the URL scheme is HTTPS. Vertx's RequestOptions.setAbsoluteURI() overrides the client
                    // SSL setting based on the actual URL scheme, so HTTP requests are not affected.
                    var options = new HttpClientOptions().setSsl(true).setTrustAll(true).setVerifyHost(false);

                    if (configuration.isUseSystemProxy()) {
                        Configuration config = ctx.getComponent(Configuration.class);
                        try {
                            options.setProxyOptions(VertxProxyOptionsUtils.buildProxyOptions(config));
                        } catch (IllegalStateException e) {
                            ctx
                                .withLogger(log)
                                .warn(
                                    "CalloutHttp requires a system proxy to be defined but some configurations are missing or not well defined: {}. Ignoring proxy",
                                    e.getMessage()
                                );
                        }
                    }

                    PoolOptions poolOptions = new PoolOptions()
                        .setHttp1MaxSize(configuration.getHttpOptions().getMaxConcurrentConnections())
                        .setMaxLifetime(maxConnectionLifetime(ctx))
                        .setMaxLifetimeUnit(TimeUnit.MILLISECONDS);

                    var vertx = ctx.getComponent(Vertx.class);
                    this.httpClient = vertx.createHttpClient(options, poolOptions);
                }
            }
        }
        return this.httpClient;
    }

    private int maxConnectionLifetime(BaseExecutionContext ctx) {
        int configuredLifetime = configuration.getHttpOptions().getMaxConnectionLifetime();
        if (configuredLifetime < 0) {
            ctx.withLogger(log).warn("CalloutHttp maxConnectionLifetime {} is negative; using 0 (no maximum lifetime)", configuredLifetime);
            return 0;
        }
        return configuredLifetime;
    }
}
