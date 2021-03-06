/*
 * Copyright 2021 Google
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
package com.google.cloud.opentelemetry.trace;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.google.devtools.cloudtrace.v2.AttributeValue;
import com.google.devtools.cloudtrace.v2.Span;
import com.google.devtools.cloudtrace.v2.TruncatableString;
import com.google.rpc.Status;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span.Kind;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.StatusData;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class TraceTranslatorTest {

  @Test
  public void testToDisplayName() {
    String serverPrefixSpanName = "Recv. mySpanName";
    String clientPrefixSpanName = "Sent. mySpanName";
    String regularSpanName = "regularSpanName";
    Kind serverSpanKind = Kind.SERVER;
    Kind clientSpanKind = Kind.CLIENT;

    assertEquals(
        serverPrefixSpanName, TraceTranslator.toDisplayName(serverPrefixSpanName, serverSpanKind));
    assertEquals(
        clientPrefixSpanName, TraceTranslator.toDisplayName(clientPrefixSpanName, clientSpanKind));
    assertEquals(
        "Recv.regularSpanName", TraceTranslator.toDisplayName(regularSpanName, serverSpanKind));
    assertEquals(
        "Sent.regularSpanName", TraceTranslator.toDisplayName(regularSpanName, clientSpanKind));
  }

  @Test
  public void testNullTruncatableStringProto() {
    assertThrows(NullPointerException.class, () -> TraceTranslator.toTruncatableStringProto(null));
  }

  @Test
  public void testToTruncatableStringProto() {
    String truncatableString = "myTruncatableString";
    TruncatableString testTruncatable = TraceTranslator.toTruncatableStringProto(truncatableString);

    assertEquals("myTruncatableString", testTruncatable.getValue());
    assertEquals(0, testTruncatable.getTruncatedByteCount());
  }

  @Test
  public void testToTimestampProto() {
    long epochNanos = TimeUnit.SECONDS.toNanos(3001) + 255;
    com.google.protobuf.Timestamp timestamp = TraceTranslator.toTimestampProto(epochNanos);

    assertEquals(3001, timestamp.getSeconds());
    assertEquals(255, timestamp.getNanos());
  }

  @Test
  public void testToAttributesProto() {
    String stringkey = "myValue";
    boolean boolKey = true;
    long longKey = 100;
    double doubleKey = 3.14;

    Attributes attributes =
        Attributes.builder()
            .put("myKey", stringkey)
            .put("http.status_code", boolKey)
            .put("anotherKey", longKey)
            .put("http.host", doubleKey)
            .build();
    Map<String, AttributeValue> fixedAttributes = new LinkedHashMap<>();
    fixedAttributes.put(
        "fixed",
        AttributeValue.newBuilder()
            .setStringValue(
                TruncatableString.newBuilder()
                    .setValue("attributes")
                    .setTruncatedByteCount(0)
                    .build())
            .build());
    fixedAttributes.put(
        "another",
        AttributeValue.newBuilder()
            .setStringValue(
                TruncatableString.newBuilder().setValue("entry").setTruncatedByteCount(0).build())
            .build());

    Span.Attributes translatedAttributes =
        TraceTranslator.toAttributesProto(attributes, fixedAttributes);

    // Because order in a hash map cannot be guaranteed, the test manually checks each entry

    assertTrue(translatedAttributes.containsAttributeMap("myKey"));
    assertEquals(
        translatedAttributes.getAttributeMapMap().get("myKey").getStringValue().getValue(),
        "myValue");

    assertTrue(translatedAttributes.containsAttributeMap("/http/host"));
    assertEquals(
        translatedAttributes.getAttributeMapMap().get("/http/host").getStringValue().getValue(),
        "3.14");

    assertTrue(translatedAttributes.containsAttributeMap("/http/status_code"));
    assertTrue(translatedAttributes.getAttributeMapMap().get("/http/status_code").getBoolValue());

    assertTrue(translatedAttributes.containsAttributeMap("anotherKey"));
    assertEquals(translatedAttributes.getAttributeMapMap().get("anotherKey").getIntValue(), 100);

    assertTrue(translatedAttributes.containsAttributeMap("fixed"));
    assertEquals(
        translatedAttributes.getAttributeMapMap().get("fixed").getStringValue().getValue(),
        "attributes");

    assertTrue(translatedAttributes.containsAttributeMap("another"));
    assertEquals(
        translatedAttributes.getAttributeMapMap().get("another").getStringValue().getValue(),
        "entry");

    assertTrue(translatedAttributes.containsAttributeMap("g.co/agent"));
    assertEquals(
        translatedAttributes.getAttributeMapMap().get("g.co/agent").getStringValue().getValue(),
        String.format(
            "opentelemetry-java %s; google-cloud-trace-exporter %s",
            TraceVersions.SDK_VERSION, TraceVersions.EXPORTER_VERSION));
  }

  @Test
  public void testToTimeEventsProto() {
    List<EventData> events = new ArrayList<>();
    EventData eventOne =
        new EventData() {
          // The SpanData.Event interfaces requires us to override these four methods
          @Override
          public long getEpochNanos() {
            return 0;
          }

          @Override
          public int getTotalAttributeCount() {
            return 1;
          }

          @Override
          public String getName() {
            return "eventOne";
          }

          @Override
          public Attributes getAttributes() {
            return Attributes.builder().put("key", "value").build();
          }
        };
    events.add(eventOne);

    Span.TimeEvents timeEvents = TraceTranslator.toTimeEventsProto(events);
    assertEquals(1, timeEvents.getTimeEventCount());

    Span.TimeEvent timeEvent = timeEvents.getTimeEvent(0);
    Span.TimeEvent.Annotation annotation = timeEvent.getAnnotation();
    assertEquals("eventOne", annotation.getDescription().getValue());

    Span.Attributes attributes = annotation.getAttributes();
    assertEquals(2, attributes.getAttributeMapCount());

    Map<String, AttributeValue> attributeMap = attributes.getAttributeMapMap();
    assertEquals("value", attributeMap.get("key").getStringValue().getValue());
    assertEquals(
        String.format(
            "opentelemetry-java %s; google-cloud-trace-exporter %s",
            TraceVersions.SDK_VERSION, TraceVersions.EXPORTER_VERSION),
        attributeMap.get("g.co/agent").getStringValue().getValue());
  }

  @Test
  public void testToStatusProto() {
    Status spanStatus = TraceTranslator.toStatusProto(StatusData.ok());

    // The int representation is 0 for canonical code "OK".
    assertEquals(0, spanStatus.getCode());
  }

  @Test
  public void testNullResourceLabels() {
    Map<String, String> nullResources = new HashMap<>();
    assertEquals(Collections.emptyMap(), TraceTranslator.getResourceLabels(nullResources));
  }

  @Test
  public void testGetResourceLabels() {
    Map<String, String> resources = new HashMap<>();
    resources.put("testOne", "testTwo");
    resources.put("another", "entry");

    Map<String, AttributeValue> resourceLabels = new LinkedHashMap<>();
    resourceLabels.put(
        "g.co/r/testOne",
        AttributeValue.newBuilder()
            .setStringValue(
                TruncatableString.newBuilder().setValue("testTwo").setTruncatedByteCount(0).build())
            .build());
    resourceLabels.put(
        "g.co/r/another",
        AttributeValue.newBuilder()
            .setStringValue(
                TruncatableString.newBuilder().setValue("entry").setTruncatedByteCount(0).build())
            .build());

    assertEquals(
        Collections.unmodifiableMap(resourceLabels), TraceTranslator.getResourceLabels(resources));
  }
}
