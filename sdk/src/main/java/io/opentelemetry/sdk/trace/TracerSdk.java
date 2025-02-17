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

import com.google.common.annotations.VisibleForTesting;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.BinaryFormat;
import io.opentelemetry.context.propagation.HttpTextFormat;
import io.opentelemetry.resources.Resource;
import io.opentelemetry.sdk.internal.Clock;
import io.opentelemetry.sdk.internal.MillisClock;
import io.opentelemetry.sdk.resources.EnvVarResource;
import io.opentelemetry.sdk.trace.config.TraceConfig;
import io.opentelemetry.trace.DefaultTracer;
import io.opentelemetry.trace.Span;
import io.opentelemetry.trace.SpanContext;
import io.opentelemetry.trace.Tracer;
import io.opentelemetry.trace.propagation.BinaryTraceContext;
import io.opentelemetry.trace.propagation.HttpTraceContext;
import io.opentelemetry.trace.unsafe.ContextUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.concurrent.GuardedBy;

/** {@link TracerSdk} is SDK implementation of {@link Tracer}. */
public class TracerSdk implements Tracer {
  private static final Logger logger = Logger.getLogger(TracerSdk.class.getName());

  private static final BinaryFormat<SpanContext> BINARY_FORMAT = new BinaryTraceContext();
  private static final HttpTextFormat<SpanContext> HTTP_TEXT_FORMAT = new HttpTraceContext();
  private final Clock clock = MillisClock.getInstance();
  private final Random random = new Random();
  private final Resource resource = EnvVarResource.getResource();

  // Reads and writes are atomic for reference variables. Use volatile to ensure that these
  // operations are visible on other CPUs as well.
  private volatile TraceConfig activeTraceConfig = TraceConfig.getDefault();
  private volatile SpanProcessor activeSpanProcessor = NoopSpanProcessor.getInstance();

  @GuardedBy("this")
  private final List<SpanProcessor> registeredSpanProcessors = new ArrayList<>();

  private volatile boolean isStopped = false;

  @Override
  public Span getCurrentSpan() {
    return ContextUtils.getValue();
  }

  @Override
  public Scope withSpan(Span span) {
    return ContextUtils.withSpan(span);
  }

  @Override
  public Span.Builder spanBuilder(String spanName) {
    if (isStopped) {
      return DefaultTracer.getInstance().spanBuilder(spanName);
    }
    return new SpanBuilderSdk(
        spanName, activeSpanProcessor, activeTraceConfig, resource, random, clock);
  }

  @Override
  public BinaryFormat<SpanContext> getBinaryFormat() {
    return BINARY_FORMAT;
  }

  @Override
  public HttpTextFormat<SpanContext> getHttpTextFormat() {
    return HTTP_TEXT_FORMAT;
  }

  /**
   * Attempts to stop all the activity for this {@link Tracer}. Calls {@link
   * SpanProcessor#shutdown()} for all registered {@link SpanProcessor}s.
   *
   * <p>This operation may block until all the Spans are processed. Must be called before turning
   * off the main application to ensure all data are processed and exported.
   *
   * <p>After this is called all the newly created {@code Span}s will be no-op.
   */
  public void shutdown() {
    synchronized (this) {
      if (isStopped) {
        logger.log(Level.WARNING, "Calling shutdown() multiple times.");
        return;
      }
      activeSpanProcessor.shutdown();
      isStopped = true;
    }
  }

  // Restarts all the activity for this Tracer. Only used for unit testing.
  @VisibleForTesting
  void unsafeRestart() {
    isStopped = false;
  }

  /**
   * Returns the active {@code TraceConfig}.
   *
   * @return the active {@code TraceConfig}.
   */
  public TraceConfig getActiveTraceConfig() {
    return activeTraceConfig;
  }

  /**
   * Updates the active {@link TraceConfig}.
   *
   * @param traceConfig the new active {@code TraceConfig}.
   */
  public void updateActiveTraceConfig(TraceConfig traceConfig) {
    activeTraceConfig = traceConfig;
  }

  /**
   * Adds a new {@code SpanProcessor} to this {@code Tracer}.
   *
   * <p>Any registered processor cause overhead, consider to use an async/batch processor especially
   * for span exporting, and export to multiple backends using the {@link
   * io.opentelemetry.sdk.trace.export.MultiSpanExporter}.
   *
   * @param spanProcessor the new {@code SpanProcessor} to be added.
   */
  public void addSpanProcessor(SpanProcessor spanProcessor) {
    synchronized (this) {
      registeredSpanProcessors.add(spanProcessor);
      activeSpanProcessor = MultiSpanProcessor.create(registeredSpanProcessors);
    }
  }
}
