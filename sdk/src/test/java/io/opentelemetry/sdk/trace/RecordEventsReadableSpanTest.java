/*
 * Copyright 2019, OpenTelemetry Authors
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

package io.opentelemetry.sdk.trace;

import static com.google.common.truth.Truth.assertThat;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import io.opentelemetry.proto.trace.v1.Span;
import io.opentelemetry.proto.trace.v1.Span.Attributes;
import io.opentelemetry.proto.trace.v1.Span.Links;
import io.opentelemetry.proto.trace.v1.Span.TimedEvents;
import io.opentelemetry.resources.Resource;
import io.opentelemetry.sdk.internal.TestClock;
import io.opentelemetry.sdk.internal.TimestampConverter;
import io.opentelemetry.sdk.trace.config.TraceConfig;
import io.opentelemetry.trace.AttributeValue;
import io.opentelemetry.trace.DefaultSpan;
import io.opentelemetry.trace.Event;
import io.opentelemetry.trace.Link;
import io.opentelemetry.trace.Span.Kind;
import io.opentelemetry.trace.SpanContext;
import io.opentelemetry.trace.SpanId;
import io.opentelemetry.trace.Status;
import io.opentelemetry.trace.TraceFlags;
import io.opentelemetry.trace.TraceId;
import io.opentelemetry.trace.Tracestate;
import io.opentelemetry.trace.util.Events;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

/** Unit tests for {@link RecordEventsReadableSpan}. */
@RunWith(JUnit4.class)
public class RecordEventsReadableSpanTest {
  private static final String SPAN_NAME = "MySpanName";
  private static final String SPAN_NEW_NAME = "NewName";
  private static final long NANOS_PER_SECOND = TimeUnit.SECONDS.toNanos(1);
  private static final long MILLIS_PER_SECOND = TimeUnit.SECONDS.toMillis(1);
  private final TraceId traceId = TestUtils.generateRandomTraceId();
  private final SpanId spanId = TestUtils.generateRandomSpanId();
  private final SpanId parentSpanId = TestUtils.generateRandomSpanId();
  private final SpanContext spanContext =
      SpanContext.create(traceId, spanId, TraceFlags.getDefault(), Tracestate.getDefault());
  private final Timestamp startTime = Timestamp.newBuilder().setSeconds(1000).build();
  private final TestClock testClock = TestClock.create(startTime);
  private final TimestampConverter timestampConverter = TimestampConverter.now(testClock);
  private final Resource resource = Resource.getEmpty();
  private final Map<String, AttributeValue> attributes = new HashMap<>();
  private final Map<String, AttributeValue> expectedAttributes = new HashMap<>();
  private final Event event =
      new SimpleEvent("event2", Collections.<String, AttributeValue>emptyMap());
  private final Link link = io.opentelemetry.trace.util.Links.create(spanContext);
  @Mock private SpanProcessor spanProcessor;
  @Rule public final ExpectedException thrown = ExpectedException.none();

  @Before
  public void setUp() {
    MockitoAnnotations.initMocks(this);
    attributes.put(
        "MyStringAttributeKey", AttributeValue.stringAttributeValue("MyStringAttributeValue"));
    attributes.put("MyLongAttributeKey", AttributeValue.longAttributeValue(123L));
    attributes.put("MyBooleanAttributeKey", AttributeValue.booleanAttributeValue(false));
    expectedAttributes.putAll(attributes);
    expectedAttributes.put(
        "MySingleStringAttributeKey",
        AttributeValue.stringAttributeValue("MySingleStringAttributeValue"));
  }

  @Test
  public void nothingChangedAfterEnd() {
    RecordEventsReadableSpan span = createTestSpan(Kind.INTERNAL);
    span.end();
    // Check that adding trace events or update fields after Span#end() does not throw any thrown
    // and are ignored.
    spanDoWork(span, Status.CANCELLED);
    Span spanProto = span.toSpanProto();
    verifySpanProto(
        spanProto,
        Attributes.getDefaultInstance(),
        TimedEvents.getDefaultInstance(),
        Links.getDefaultInstance(),
        SPAN_NAME,
        startTime,
        startTime,
        Status.OK,
        0);
  }

  @Test
  public void endSpanTwice_DoNotCrash() {
    RecordEventsReadableSpan span = createTestSpan(Kind.INTERNAL);
    span.end();
    span.end();
  }

  @Test
  public void toSpanProto_ActiveSpan() {
    RecordEventsReadableSpan span = createTestSpan(Kind.INTERNAL);
    try {
      spanDoWork(span, null);
      Span spanProto = span.toSpanProto();
      long timeInNanos = (startTime.getSeconds() + 1) * NANOS_PER_SECOND;
      TimedEvent timedEvent = TimedEvent.create(timeInNanos, event);
      verifySpanProto(
          spanProto,
          TraceProtoUtils.toProtoAttributes(expectedAttributes, 0),
          TraceProtoUtils.toProtoTimedEvents(
              Collections.singletonList(timedEvent), 0, timestampConverter),
          TraceProtoUtils.toProtoLinks(Collections.singletonList(link), 0),
          SPAN_NEW_NAME,
          startTime,
          testClock.now(),
          Status.OK,
          1);
    } finally {
      span.end();
    }
  }

  @Test
  public void toSpanProto_EndedSpan() {
    RecordEventsReadableSpan span = createTestSpan(Kind.INTERNAL);
    try {
      spanDoWork(span, Status.CANCELLED);
    } finally {
      span.end();
    }
    Mockito.verify(spanProcessor, Mockito.times(1)).onEnd(span);
    Span spanProto = span.toSpanProto();
    long timeInNanos = (startTime.getSeconds() + 1) * NANOS_PER_SECOND;
    TimedEvent timedEvent = TimedEvent.create(timeInNanos, event);
    verifySpanProto(
        spanProto,
        TraceProtoUtils.toProtoAttributes(expectedAttributes, 0),
        TraceProtoUtils.toProtoTimedEvents(
            Collections.singletonList(timedEvent), 0, timestampConverter),
        TraceProtoUtils.toProtoLinks(Collections.singletonList(link), 0),
        SPAN_NEW_NAME,
        startTime,
        testClock.now(),
        Status.CANCELLED,
        1);
  }

  @Test
  public void toSpanProto_RootSpan() {
    RecordEventsReadableSpan span = createTestRootSpan();
    try {
      spanDoWork(span, null);
    } finally {
      span.end();
    }
    Span spanProto = span.toSpanProto();
    assertThat(spanProto.getParentSpanId()).isEqualTo(ByteString.EMPTY);
  }

  @Test
  public void toSpanProto_WithInitialAttributes() {
    RecordEventsReadableSpan span = createTestSpanWithAttributes(attributes);
    span.end();
    Span spanProto = span.toSpanProto();
    assertThat(spanProto.getAttributes().getAttributeMapCount()).isEqualTo(attributes.size());
  }

  @Test
  public void setStatus() {
    RecordEventsReadableSpan span = createTestSpan(Kind.CONSUMER);
    try {
      testClock.advanceMillis(MILLIS_PER_SECOND);
      assertThat(span.getStatus()).isEqualTo(Status.OK);
      span.setStatus(Status.CANCELLED);
      assertThat(span.getStatus()).isEqualTo(Status.CANCELLED);
    } finally {
      span.end();
    }
    assertThat(span.getStatus()).isEqualTo(Status.CANCELLED);
  }

  @Test
  public void getSpanKind() {
    RecordEventsReadableSpan span = createTestSpan(Kind.SERVER);
    try {
      assertThat(span.getKind()).isEqualTo(Kind.SERVER);
    } finally {
      span.end();
    }
  }

  @Test
  public void getAndUpdateSpanName() {
    RecordEventsReadableSpan span = createTestRootSpan();
    try {
      assertThat(span.getName()).isEqualTo(SPAN_NAME);
      span.updateName(SPAN_NEW_NAME);
      assertThat(span.getName()).isEqualTo(SPAN_NEW_NAME);
    } finally {
      span.end();
    }
  }

  @Test
  public void getLatencyNs_ActiveSpan() {
    RecordEventsReadableSpan span = createTestSpan(Kind.INTERNAL);
    try {
      testClock.advanceMillis(MILLIS_PER_SECOND);
      long elapsedTimeNanos1 =
          (testClock.now().getSeconds() - startTime.getSeconds()) * NANOS_PER_SECOND;
      assertThat(span.getLatencyNs()).isEqualTo(elapsedTimeNanos1);
      testClock.advanceMillis(MILLIS_PER_SECOND);
      long elapsedTimeNanos2 =
          (testClock.now().getSeconds() - startTime.getSeconds()) * NANOS_PER_SECOND;
      assertThat(span.getLatencyNs()).isEqualTo(elapsedTimeNanos2);
    } finally {
      span.end();
    }
  }

  @Test
  public void getLatencyNs_EndedSpan() {
    RecordEventsReadableSpan span = createTestSpan(Kind.INTERNAL);
    testClock.advanceMillis(MILLIS_PER_SECOND);
    span.end();
    long elapsedTimeNanos =
        (testClock.now().getSeconds() - startTime.getSeconds()) * NANOS_PER_SECOND;
    assertThat(span.getLatencyNs()).isEqualTo(elapsedTimeNanos);
    testClock.advanceMillis(MILLIS_PER_SECOND);
    assertThat(span.getLatencyNs()).isEqualTo(elapsedTimeNanos);
  }

  @Test
  public void setAttribute() {
    RecordEventsReadableSpan span = createTestRootSpan();
    try {
      span.setAttribute("StringKey", "StringVal");
      span.setAttribute("LongKey", 1000L);
      span.setAttribute("DoubleKey", 10.0);
      span.setAttribute("BooleanKey", false);
    } finally {
      span.end();
    }
    Span spanProto = span.toSpanProto();
    assertThat(spanProto.getAttributes().getAttributeMapCount()).isEqualTo(4);
  }

  @Test
  public void addEvent() {
    RecordEventsReadableSpan span = createTestRootSpan();
    try {
      span.addEvent("event1");
      span.addEvent("event2", attributes);
      span.addEvent(Events.create("event3"));
    } finally {
      span.end();
    }
    Span spanProto = span.toSpanProto();
    assertThat(spanProto.getTimeEvents().getTimedEventCount()).isEqualTo(3);
  }

  @Test
  public void addLink() {
    RecordEventsReadableSpan span = createTestRootSpan();
    try {
      span.addLink(DefaultSpan.getInvalid().getContext());
      span.addLink(spanContext, attributes);
      span.addLink(io.opentelemetry.trace.util.Links.create(DefaultSpan.getInvalid().getContext()));
    } finally {
      span.end();
    }
    Span spanProto = span.toSpanProto();
    assertThat(spanProto.getLinks().getLinkCount()).isEqualTo(3);
  }

  @Test
  public void droppingAttributes() {
    final int maxNumberOfAttributes = 8;
    TraceConfig traceConfig =
        TraceConfig.getDefault()
            .toBuilder()
            .setMaxNumberOfAttributes(maxNumberOfAttributes)
            .build();
    RecordEventsReadableSpan span = createTestSpan(traceConfig);
    try {
      for (int i = 0; i < 2 * maxNumberOfAttributes; i++) {
        span.setAttribute("MyStringAttributeKey" + i, AttributeValue.longAttributeValue(i));
      }
      Span spanProto = span.toSpanProto();
      assertThat(spanProto.getAttributes().getDroppedAttributesCount())
          .isEqualTo(maxNumberOfAttributes);
      assertThat(spanProto.getAttributes().getAttributeMapMap().size())
          .isEqualTo(maxNumberOfAttributes);
      for (int i = 0; i < maxNumberOfAttributes; i++) {
        AttributeValue expectedValue = AttributeValue.longAttributeValue(i + maxNumberOfAttributes);
        assertThat(
                spanProto
                    .getAttributes()
                    .getAttributeMapMap()
                    .get("MyStringAttributeKey" + (i + maxNumberOfAttributes)))
            .isEqualTo(TraceProtoUtils.toProtoAttributeValue(expectedValue));
      }
    } finally {
      span.end();
    }
    Span spanProto = span.toSpanProto();
    assertThat(spanProto.getAttributes().getDroppedAttributesCount())
        .isEqualTo(maxNumberOfAttributes);
    assertThat(spanProto.getAttributes().getAttributeMapMap().size())
        .isEqualTo(maxNumberOfAttributes);
    for (int i = 0; i < maxNumberOfAttributes; i++) {
      AttributeValue expectedValue = AttributeValue.longAttributeValue(i + maxNumberOfAttributes);
      assertThat(
              spanProto
                  .getAttributes()
                  .getAttributeMapMap()
                  .get("MyStringAttributeKey" + (i + maxNumberOfAttributes)))
          .isEqualTo(TraceProtoUtils.toProtoAttributeValue(expectedValue));
    }
  }

  @Test
  public void droppingAndAddingAttributes() {
    final int maxNumberOfAttributes = 8;
    TraceConfig traceConfig =
        TraceConfig.getDefault()
            .toBuilder()
            .setMaxNumberOfAttributes(maxNumberOfAttributes)
            .build();
    RecordEventsReadableSpan span = createTestSpan(traceConfig);
    try {
      for (int i = 0; i < 2 * maxNumberOfAttributes; i++) {
        span.setAttribute("MyStringAttributeKey" + i, AttributeValue.longAttributeValue(i));
      }
      Span spanProto = span.toSpanProto();
      assertThat(spanProto.getAttributes().getDroppedAttributesCount())
          .isEqualTo(maxNumberOfAttributes);
      assertThat(spanProto.getAttributes().getAttributeMapMap().size())
          .isEqualTo(maxNumberOfAttributes);
      for (int i = 0; i < maxNumberOfAttributes; i++) {
        AttributeValue expectedValue = AttributeValue.longAttributeValue(i + maxNumberOfAttributes);
        assertThat(
                spanProto
                    .getAttributes()
                    .getAttributeMapMap()
                    .get("MyStringAttributeKey" + (i + maxNumberOfAttributes)))
            .isEqualTo(TraceProtoUtils.toProtoAttributeValue(expectedValue));
      }

      for (int i = 0; i < maxNumberOfAttributes / 2; i++) {
        span.setAttribute("MyStringAttributeKey" + i, AttributeValue.longAttributeValue(i));
      }
      spanProto = span.toSpanProto();
      assertThat(spanProto.getAttributes().getDroppedAttributesCount())
          .isEqualTo(maxNumberOfAttributes * 3 / 2);
      assertThat(spanProto.getAttributes().getAttributeMapMap().size())
          .isEqualTo(maxNumberOfAttributes);
      // Test that we still have in the attributes map the latest maxNumberOfAttributes / 2 entries.
      for (int i = 0; i < maxNumberOfAttributes / 2; i++) {
        int val = i + maxNumberOfAttributes * 3 / 2;
        AttributeValue expectedValue = AttributeValue.longAttributeValue(val);
        assertThat(spanProto.getAttributes().getAttributeMapMap().get("MyStringAttributeKey" + val))
            .isEqualTo(TraceProtoUtils.toProtoAttributeValue(expectedValue));
      }
      // Test that we have the newest re-added initial entries.
      for (int i = 0; i < maxNumberOfAttributes / 2; i++) {
        AttributeValue expectedValue = AttributeValue.longAttributeValue(i);
        assertThat(spanProto.getAttributes().getAttributeMapMap().get("MyStringAttributeKey" + i))
            .isEqualTo(TraceProtoUtils.toProtoAttributeValue(expectedValue));
      }
    } finally {
      span.end();
    }
  }

  @Test
  public void droppingEvents() {
    final int maxNumberOfEvents = 8;
    TraceConfig traceConfig =
        TraceConfig.getDefault().toBuilder().setMaxNumberOfEvents(maxNumberOfEvents).build();
    RecordEventsReadableSpan span = createTestSpan(traceConfig);
    try {
      for (int i = 0; i < 2 * maxNumberOfEvents; i++) {
        span.addEvent(event);
        testClock.advanceMillis(MILLIS_PER_SECOND);
      }
      Span spanProto = span.toSpanProto();
      assertThat(spanProto.getTimeEvents().getDroppedTimedEventsCount())
          .isEqualTo(maxNumberOfEvents);

      assertThat(spanProto.getTimeEvents().getTimedEventList().size()).isEqualTo(maxNumberOfEvents);
      for (int i = 0; i < maxNumberOfEvents; i++) {
        long timeInNanos = (startTime.getSeconds() + maxNumberOfEvents + i) * NANOS_PER_SECOND;
        Span.TimedEvent expectedEvent =
            TraceProtoUtils.toProtoTimedEvent(
                TimedEvent.create(timeInNanos, event), timestampConverter);
        assertThat(spanProto.getTimeEvents().getTimedEventList().get(i)).isEqualTo(expectedEvent);
      }
    } finally {
      span.end();
    }
    Span spanProto = span.toSpanProto();
    assertThat(spanProto.getTimeEvents().getDroppedTimedEventsCount()).isEqualTo(maxNumberOfEvents);
    assertThat(spanProto.getTimeEvents().getTimedEventList().size()).isEqualTo(maxNumberOfEvents);
    for (int i = 0; i < maxNumberOfEvents; i++) {
      long timeInNanos = (startTime.getSeconds() + maxNumberOfEvents + i) * NANOS_PER_SECOND;
      Span.TimedEvent expectedEvent =
          TraceProtoUtils.toProtoTimedEvent(
              TimedEvent.create(timeInNanos, event), timestampConverter);
      assertThat(spanProto.getTimeEvents().getTimedEventList().get(i)).isEqualTo(expectedEvent);
    }
  }

  @Test
  public void droppingLinks() {
    final int maxNumberOfLinks = 8;
    TraceConfig traceConfig =
        TraceConfig.getDefault().toBuilder().setMaxNumberOfLinks(maxNumberOfLinks).build();
    RecordEventsReadableSpan span = createTestSpan(traceConfig);
    try {
      for (int i = 0; i < 2 * maxNumberOfLinks; i++) {
        span.addLink(link);
      }
      Span spanProto = span.toSpanProto();
      assertThat(spanProto.getLinks().getDroppedLinksCount()).isEqualTo(maxNumberOfLinks);
      assertThat(spanProto.getLinks().getLinkList().size()).isEqualTo(maxNumberOfLinks);
      for (int i = 0; i < maxNumberOfLinks; i++) {
        assertThat(spanProto.getLinks().getLinkList().get(i))
            .isEqualTo(TraceProtoUtils.toProtoLink(link));
      }
    } finally {
      span.end();
    }
    Span spanProto = span.toSpanProto();
    assertThat(spanProto.getLinks().getDroppedLinksCount()).isEqualTo(maxNumberOfLinks);
    assertThat(spanProto.getLinks().getLinkList().size()).isEqualTo(maxNumberOfLinks);
    for (int i = 0; i < maxNumberOfLinks; i++) {
      assertThat(spanProto.getLinks().getLinkList().get(i))
          .isEqualTo(TraceProtoUtils.toProtoLink(link));
    }
  }

  private RecordEventsReadableSpan createTestSpanWithAttributes(
      Map<String, AttributeValue> attributes) {
    return createTestSpan(Kind.INTERNAL, TraceConfig.getDefault(), null, attributes);
  }

  private RecordEventsReadableSpan createTestRootSpan() {
    return createTestSpan(
        Kind.INTERNAL,
        TraceConfig.getDefault(),
        null,
        Collections.<String, AttributeValue>emptyMap());
  }

  private RecordEventsReadableSpan createTestSpan(Kind kind) {
    return createTestSpan(
        kind,
        TraceConfig.getDefault(),
        parentSpanId,
        Collections.<String, AttributeValue>emptyMap());
  }

  private RecordEventsReadableSpan createTestSpan(TraceConfig config) {
    return createTestSpan(
        Kind.INTERNAL, config, parentSpanId, Collections.<String, AttributeValue>emptyMap());
  }

  private RecordEventsReadableSpan createTestSpan(
      Kind kind,
      TraceConfig config,
      @Nullable SpanId parentSpanId,
      Map<String, AttributeValue> attributes) {
    RecordEventsReadableSpan span =
        RecordEventsReadableSpan.startSpan(
            spanContext,
            SPAN_NAME,
            kind,
            parentSpanId,
            config,
            spanProcessor,
            timestampConverter,
            testClock,
            resource,
            attributes);
    Mockito.verify(spanProcessor, Mockito.times(1)).onStart(span);
    return span;
  }

  private void spanDoWork(RecordEventsReadableSpan span, @Nullable Status status) {
    span.setAttribute(
        "MySingleStringAttributeKey",
        AttributeValue.stringAttributeValue("MySingleStringAttributeValue"));
    for (Map.Entry<String, AttributeValue> attribute : attributes.entrySet()) {
      span.setAttribute(attribute.getKey(), attribute.getValue());
    }
    testClock.advanceMillis(MILLIS_PER_SECOND);
    span.addEvent(event);
    span.addLink(link);
    testClock.advanceMillis(MILLIS_PER_SECOND);
    span.addChild();
    span.updateName(SPAN_NEW_NAME);
    if (status != null) {
      span.setStatus(status);
    }
  }

  private void verifySpanProto(
      Span spanProto,
      Attributes attributes,
      TimedEvents timedEvents,
      Links links,
      String spanName,
      Timestamp startTime,
      Timestamp endTime,
      Status status,
      int childCount) {
    assertThat(spanProto.getTraceId()).isEqualTo(TraceProtoUtils.toProtoTraceId(traceId));
    assertThat(spanProto.getSpanId()).isEqualTo(TraceProtoUtils.toProtoSpanId(spanId));
    assertThat(spanProto.getParentSpanId()).isEqualTo(TraceProtoUtils.toProtoSpanId(parentSpanId));
    assertThat(spanProto.getTracestate())
        .isEqualTo(TraceProtoUtils.toProtoTracestate(Tracestate.getDefault()));
    assertThat(spanProto.getResource()).isEqualTo(TraceProtoUtils.toProtoResource(resource));
    assertThat(spanProto.getName()).isEqualTo(spanName);
    assertThat(spanProto.getAttributes()).isEqualTo(attributes);
    assertThat(spanProto.getTimeEvents()).isEqualTo(timedEvents);
    assertThat(spanProto.getLinks()).isEqualTo(links);
    assertThat(spanProto.getStartTime()).isEqualTo(startTime);
    assertThat(spanProto.getEndTime()).isEqualTo(endTime);
    assertThat(spanProto.getStatus().getCode()).isEqualTo(status.getCanonicalCode().value());
    assertThat(spanProto.getChildSpanCount().getValue()).isEqualTo(childCount);
  }

  private static final class SimpleEvent implements Event {

    private final String name;
    private final Map<String, AttributeValue> attributes;

    private SimpleEvent(String name, Map<String, AttributeValue> attributes) {
      this.name = name;
      this.attributes = attributes;
    }

    @Override
    public String getName() {
      return name;
    }

    @Override
    public Map<String, AttributeValue> getAttributes() {
      return attributes;
    }
  }
}
