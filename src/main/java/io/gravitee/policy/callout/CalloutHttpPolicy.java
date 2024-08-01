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

import static java.util.stream.Collectors.toList;

import io.gravitee.gateway.reactive.api.ExecutionFailure;
import io.gravitee.gateway.reactive.api.context.HttpExecutionContext;
import io.gravitee.gateway.reactive.api.policy.Policy;
import io.gravitee.node.api.configuration.Configuration;
import io.gravitee.node.vertx.proxy.VertxProxyOptionsUtils;
import io.gravitee.policy.callout.configuration.CalloutHttpPolicyConfiguration;
import io.gravitee.policy.callout.configuration.HttpHeader;
import io.gravitee.policy.v3.callout.CalloutHttpPolicyV3;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.RequestOptions;
import io.vertx.rxjava3.core.Vertx;
import io.vertx.rxjava3.core.buffer.Buffer;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
@Slf4j
public class CalloutHttpPolicy extends CalloutHttpPolicyV3 implements Policy {

    public CalloutHttpPolicy(CalloutHttpPolicyConfiguration configuration) {
        super(configuration);
    }

    @Override
    public String id() {
        return "policy-http-callout";
    }

    @Override
    public Completable onRequest(HttpExecutionContext ctx) {
        return Completable.defer(() -> doCallOut(ctx));
    }

    @Override
    public Completable onResponse(HttpExecutionContext ctx) {
        return Completable.defer(() -> doCallOut(ctx));
    }

    private Completable doCallOut(HttpExecutionContext ctx) {
        var templateEngine = ctx.getTemplateEngine();
        var vertx = ctx.getComponent(Vertx.class);

        var url = templateEngine.eval(configuration.getUrl(), String.class).switchIfEmpty(Single.just(configuration.getUrl()));
        var body = configuration.getBody() != null
            ? templateEngine
                .eval(configuration.getBody(), String.class)
                .map(Optional::of)
                .switchIfEmpty(Single.just(Optional.ofNullable(configuration.getBody())))
            : Single.just(Optional.<String>empty());
        var headers = Flowable
            .fromIterable(configuration.getHeaders())
            .flatMap(header -> {
                if (header.getValue() != null) {
                    return templateEngine
                        .eval(header.getValue(), String.class)
                        .map(value -> new HttpHeader(header.getName(), value))
                        .switchIfEmpty(Single.just(header))
                        .toFlowable();
                }
                return Flowable.empty();
            })
            .collect(toList());

        return Single
            .zip(url, body, headers, Req::new)
            .flatMap(reqConfig -> {
                var target = URI.create(reqConfig.url);
                var httpClient = vertx.createHttpClient(buildHttpClientOptions(ctx, target));
                var requestOpts = new RequestOptions().setAbsoluteURI(reqConfig.url).setMethod(convert(configuration.getMethod()));

                return httpClient
                    .rxRequest(requestOpts)
                    .flatMap(req -> {
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
                    });
            })
            .onErrorResumeNext(throwable -> Single.error(new CalloutException(throwable)))
            .flatMap(httpClientResponse ->
                httpClientResponse
                    .body()
                    .map(responseBody -> new CalloutResponse(httpClientResponse.getDelegate(), responseBody.toString()))
            )
            .flatMapCompletable(calloutResponse -> processCalloutResponse(ctx, calloutResponse))
            .onErrorResumeNext(th -> {
                if (th instanceof CalloutException && configuration.isExitOnError()) {
                    return ctx.interruptWith(
                        new ExecutionFailure(configuration.getErrorStatusCode()).key(CALLOUT_HTTP_ERROR).message(th.getCause().getMessage())
                    );
                }
                return Completable.error(th);
            });
    }

    private HttpClientOptions buildHttpClientOptions(HttpExecutionContext ctx, URI target) {
        var options = new HttpClientOptions();
        if (HTTPS_SCHEME.equalsIgnoreCase(target.getScheme())) {
            options.setSsl(true).setTrustAll(true).setVerifyHost(false);
        }

        if (configuration.isUseSystemProxy()) {
            Configuration configuration = ctx.getComponent(Configuration.class);
            try {
                options.setProxyOptions(VertxProxyOptionsUtils.buildProxyOptions(configuration));
            } catch (IllegalStateException e) {
                log.warn(
                    "CalloutHttp requires a system proxy to be defined but some configurations are missing or not well defined: {}. Ignoring proxy",
                    e.getMessage()
                );
            }
        }
        return options;
    }

    private Completable processCalloutResponse(HttpExecutionContext ctx, CalloutResponse calloutResponse) {
        if (configuration.isFireAndForget()) {
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
                        return processSuccess(ctx);
                    }

                    return processError(ctx);
                });
        }

        return processSuccess(ctx);
    }

    private Completable processSuccess(HttpExecutionContext ctx) {
        return Flowable
            .fromIterable(configuration.getVariables())
            .flatMapCompletable(variable -> {
                ctx.setAttribute(variable.getName(), null);
                return Maybe
                    .just(variable.getValue())
                    .flatMap(value -> ctx.getTemplateEngine().eval(value, String.class))
                    .doOnSuccess(value -> ctx.setAttribute(variable.getName(), value))
                    .ignoreElement();
            })
            .doOnComplete(() -> ctx.getTemplateEngine().getTemplateContext().setVariable(TEMPLATE_VARIABLE, null));
    }

    private Completable processError(HttpExecutionContext ctx) {
        return Maybe
            .fromSupplier(configuration::getErrorContent)
            .flatMap(content -> ctx.getTemplateEngine().eval(content, String.class))
            .switchIfEmpty(Single.just("Request is terminated."))
            .flatMapCompletable(errorContent ->
                ctx.interruptWith(new ExecutionFailure(configuration.getErrorStatusCode()).key(CALLOUT_EXIT_ON_ERROR).message(errorContent))
            );
    }

    record Req(String url, Optional<String> body, List<HttpHeader> headerList) {}
}
