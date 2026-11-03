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

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class CountingCalloutTarget {

    public static final String URL_PLACEHOLDER = "COUNTING_CALLOUT_URL";

    private final Vertx vertx = Vertx.vertx();
    private final AtomicInteger acceptedConnections = new AtomicInteger();
    private final Semaphore closedConnections = new Semaphore(0);
    private final AtomicInteger testGeneration = new AtomicInteger();
    private final HttpServer server;

    public CountingCalloutTarget() {
        server = vertx
            .createHttpServer()
            .connectionHandler(connection -> {
                acceptedConnections.incrementAndGet();
                int acceptedInGeneration = testGeneration.get();
                // A connection left by a previous test can close mid-test; only count closes of this test's connections.
                connection.closeHandler(closed -> {
                    if (acceptedInGeneration == testGeneration.get()) {
                        closedConnections.release();
                    }
                });
            })
            .requestHandler(request -> request.response().end("response from callout"))
            .listen(0)
            .toCompletionStage()
            .toCompletableFuture()
            .join();
    }

    public String url() {
        return "http://localhost:" + server.actualPort();
    }

    public void reset() {
        testGeneration.incrementAndGet();
        acceptedConnections.set(0);
        closedConnections.drainPermits();
    }

    public boolean awaitConnectionClose(long timeout, TimeUnit unit) throws InterruptedException {
        return closedConnections.tryAcquire(timeout, unit);
    }

    public int acceptedConnections() {
        return acceptedConnections.get();
    }

    public void close() {
        vertx.close().toCompletionStage().toCompletableFuture().join();
    }
}
