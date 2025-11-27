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

import io.gravitee.common.http.HttpStatusCode;
import io.gravitee.gateway.api.buffer.Buffer;
import io.gravitee.gateway.api.http.HttpHeaderNames;
import io.gravitee.gateway.reactive.api.ExecutionFailure;
import io.gravitee.gateway.reactive.api.context.HttpExecutionContext;
import io.gravitee.gateway.reactive.api.context.MessageExecutionContext;
import io.gravitee.gateway.reactive.api.el.EvaluableMessage;
import io.gravitee.gateway.reactive.api.policy.Policy;
import io.gravitee.policy.api.PolicyConfiguration;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;

public class AssignContentPolicy implements Policy {

    public record AssignContentPolicyConfiguration(String body) implements PolicyConfiguration {}

    private final AssignContentPolicyConfiguration configuration;

    public AssignContentPolicy(AssignContentPolicyConfiguration configuration) {
        this.configuration = configuration;
    }

    @Override
    public String id() {
        return "assign-content";
    }

    @Override
    public Completable onResponse(HttpExecutionContext ctx) {
        return ctx
            .response()
            .onBody(body ->
                body
                    .flatMap(content ->
                        ctx
                            .getTemplateEngine()
                            .eval(configuration.body(), String.class)
                            .flatMap(value -> Maybe.just(Buffer.buffer(value)))
                    )
                    .doOnSuccess(buffer -> ctx.response().headers().set(HttpHeaderNames.CONTENT_LENGTH, Integer.toString(buffer.length())))
                    .onErrorResumeNext(error ->
                        ctx.interruptBodyWith(
                            new ExecutionFailure(HttpStatusCode.INTERNAL_SERVER_ERROR_500).message(
                                "Unable to assign body content: " + error.getMessage()
                            )
                        )
                    )
            );
    }

    @Override
    public Completable onMessageResponse(MessageExecutionContext ctx) {
        return ctx
            .response()
            .onMessage(message -> {
                ctx.getTemplateEngine().getTemplateContext().setVariable("message", new EvaluableMessage(message));
                return ctx
                    .getTemplateEngine()
                    .eval(configuration.body(), String.class)
                    .flatMap(content -> Maybe.just(message.content(Buffer.buffer(content))))
                    .doOnSuccess(buffer ->
                        ctx.response().headers().set(HttpHeaderNames.CONTENT_LENGTH, Integer.toString(message.content().length()))
                    )
                    .onErrorResumeNext(error ->
                        ctx.interruptMessageWith(
                            new ExecutionFailure(HttpStatusCode.INTERNAL_SERVER_ERROR_500).message(
                                "Unable to assign body content: " + error.getMessage()
                            )
                        )
                    );
            });
    }
}
