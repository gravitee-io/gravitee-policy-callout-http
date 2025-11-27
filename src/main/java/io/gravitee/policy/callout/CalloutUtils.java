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

import io.gravitee.el.TemplateEngine;
import io.gravitee.policy.callout.configuration.CalloutHttpPolicyConfiguration;
import io.gravitee.policy.callout.configuration.HttpHeader;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import java.util.Optional;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

/**
 * @author Anthony CALLAERT (anthony.callaert at graviteesource.com)
 * @author GraviteeSource Team
 */
@UtilityClass
@Slf4j
public class CalloutUtils {

    Single<CalloutHttpPolicy.Req> prepareCalloutRequest(TemplateEngine templateEngine, CalloutHttpPolicyConfiguration configuration) {
        var url = templateEngine.eval(configuration.getUrl(), String.class).switchIfEmpty(Single.just(configuration.getUrl()));
        var body = configuration.getBody() != null
            ? templateEngine
                .eval(configuration.getBody(), String.class)
                .map(Optional::of)
                .switchIfEmpty(Single.just(Optional.ofNullable(configuration.getBody())))
            : Single.just(Optional.<String>empty());
        var headers = Flowable.fromIterable(configuration.getHeaders())
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

        return Single.zip(url, body, headers, CalloutHttpPolicy.Req::new);
    }
}
