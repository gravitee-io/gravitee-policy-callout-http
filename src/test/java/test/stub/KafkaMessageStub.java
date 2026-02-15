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
package test.stub;

import io.gravitee.gateway.api.buffer.Buffer;
import io.gravitee.gateway.reactive.api.message.Message;
import io.gravitee.gateway.reactive.api.message.kafka.KafkaMessage;
import io.reactivex.rxjava3.annotations.Nullable;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author GraviteeSource Team
 */
public class KafkaMessageStub implements KafkaMessage {

    private final Map<String, Buffer> recordHeaders = new ConcurrentHashMap<>();
    private Buffer content;

    public KafkaMessageStub(String content) {
        this.content = Buffer.buffer(content);
    }

    @Override
    public Map<String, Buffer> recordHeaders() {
        return recordHeaders;
    }

    @Override
    public KafkaMessage putRecordHeader(String key, Buffer value) {
        recordHeaders.put(key, value);
        return this;
    }

    @Override
    public KafkaMessage removeRecordHeader(String key) {
        recordHeaders.remove(key);
        return this;
    }

    @Override
    public Buffer key() {
        return null;
    }

    @Override
    public KafkaMessage key(Buffer buffer) {
        return null;
    }

    @Override
    public KafkaMessage key(String s) {
        return null;
    }

    @Override
    public long offset() {
        return 0;
    }

    @Override
    public int sequence() {
        return 0;
    }

    @Override
    public int indexPartition() {
        return 0;
    }

    @Override
    public String topic() {
        return "";
    }

    @Override
    public int sizeInBytes() {
        return 0;
    }

    @Override
    public String id() {
        return "";
    }

    @Override
    public String correlationId() {
        return "";
    }

    @Override
    public String parentCorrelationId() {
        return "";
    }

    @Override
    public long timestamp() {
        return 0;
    }

    @Override
    public boolean error() {
        return false;
    }

    @Override
    public Message error(boolean error) {
        return null;
    }

    @Override
    public Map<String, Object> metadata() {
        return Map.of();
    }

    @Override
    public @Nullable Buffer content() {
        return content;
    }

    @Override
    public Message content(Buffer content) {
        this.content = content;
        return this;
    }

    @Override
    public Message content(String content) {
        this.content = Buffer.buffer(content);
        return this;
    }

    @Override
    public <T> T attribute(String name) {
        return null;
    }

    @Override
    public <T> List<T> attributeAsList(String name) {
        return List.of();
    }

    @Override
    public Message attribute(String name, Object value) {
        return this;
    }

    @Override
    public Message removeAttribute(String name) {
        return this;
    }

    @Override
    public Set<String> attributeNames() {
        return Set.of();
    }

    @Override
    public <T> Map<String, T> attributes() {
        return Map.of();
    }

    @Override
    public <T> T internalAttribute(String name) {
        return null;
    }

    @Override
    public Message internalAttribute(String name, Object value) {
        return this;
    }

    @Override
    public Message removeInternalAttribute(String name) {
        return this;
    }

    @Override
    public Set<String> internalAttributeNames() {
        return Set.of();
    }

    @Override
    public <T> Map<String, T> internalAttributes() {
        return Map.of();
    }
}
