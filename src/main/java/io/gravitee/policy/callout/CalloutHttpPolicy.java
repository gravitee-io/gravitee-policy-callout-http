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

import io.gravitee.gateway.reactive.api.context.HttpExecutionContext;
import io.gravitee.gateway.reactive.api.context.MessageExecutionContext;
import io.gravitee.gateway.reactive.api.policy.Policy;
import io.gravitee.policy.callout.configuration.CalloutHttpPolicyConfiguration;
import io.gravitee.policy.v3.callout.CalloutHttpPolicyV3;
import io.reactivex.rxjava3.core.Completable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
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
        return Policy.super.onRequest(ctx);
    }

    @Override
    public Completable onResponse(HttpExecutionContext ctx) {
        return Policy.super.onResponse(ctx);
    }

    @Override
    public Completable onMessageRequest(MessageExecutionContext ctx) {
        return Policy.super.onMessageRequest(ctx);
    }

    @Override
    public Completable onMessageResponse(MessageExecutionContext ctx) {
        return Policy.super.onMessageResponse(ctx);
    }
}
