/* Copyright 2020 The OpenTracing Authors
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

package io.opentracing.contrib.specialagent.rule.kafka.streams;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.streams.processor.internals.StampedRecord;

import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.Tracer;
import io.opentracing.Tracer.SpanBuilder;
import io.opentracing.contrib.kafka.TracingKafkaUtils;
import io.opentracing.contrib.specialagent.LocalSpanContext;
import io.opentracing.contrib.specialagent.OpenTracingApiUtil;
import io.opentracing.tag.Tags;
import io.opentracing.util.GlobalTracer;

public class KafkaStreamsAgentIntercept {
  static final String COMPONENT_NAME = "kafka-streams";

  public static void onNextRecordExit(final Object record) {
    if (record == null)
      return;

    if (LocalSpanContext.get(COMPONENT_NAME) != null) {
      LocalSpanContext.get(COMPONENT_NAME).increment();
      return;
    }

    final Tracer tracer = GlobalTracer.get();
    final StampedRecord stampedRecord = (StampedRecord)record;
    final SpanBuilder spanBuilder = tracer.buildSpan("consume")
      .withTag(Tags.COMPONENT, COMPONENT_NAME)
      .withTag(Tags.SPAN_KIND, Tags.SPAN_KIND_CONSUMER)
      .withTag(Tags.PEER_SERVICE, "kafka")
      .withTag("partition", stampedRecord.partition())
      .withTag("offset", stampedRecord.offset());

    if (stampedRecord.topic() != null)
      spanBuilder.withTag(Tags.MESSAGE_BUS_DESTINATION, stampedRecord.topic());

    final SpanContext parentContext = TracingKafkaUtils.extractSpanContext(stampedRecord.value.headers(), tracer);
    if (parentContext != null)
      spanBuilder.asChildOf(parentContext);

    final Span span = spanBuilder.start();
    LocalSpanContext.set(COMPONENT_NAME, span, tracer.activateSpan(span));
  }

  public static void onProcessExit(final Throwable thrown) {
    final LocalSpanContext context = LocalSpanContext.get(COMPONENT_NAME);
    if (context == null || context.decrementAndGet() != 0)
      return;

    if (thrown != null)
      OpenTracingApiUtil.setErrorTag(context.getSpan(), thrown);

    context.closeAndFinish();
  }

  @SuppressWarnings("rawtypes")
  public static void onDeserializeExit(final Object returned, final Object record) {
    if (returned == null || record == null)
      return;

    final ConsumerRecord rawRecord = (ConsumerRecord)record;
    final Tracer tracer = GlobalTracer.get();
    final SpanContext spanContext = TracingKafkaUtils.extractSpanContext(rawRecord.headers(), tracer);
    if (spanContext != null) {
      final ConsumerRecord returnedRecord = (ConsumerRecord)returned;
      TracingKafkaUtils.inject(spanContext, returnedRecord.headers(), tracer);
    }
  }
}