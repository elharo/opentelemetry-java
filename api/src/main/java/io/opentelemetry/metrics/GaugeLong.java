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

package io.opentelemetry.metrics;

import io.opentelemetry.metrics.GaugeLong.Handle;
import java.util.List;
import javax.annotation.concurrent.ThreadSafe;

/**
 * Gauge metric, to report instantaneous measurement of an long value. Gauges can go both up and
 * down. The gauges values can be negative.
 *
 * <p>Example:
 *
 * <pre>{@code
 * class YourClass {
 *
 *   private static final Meter meter = OpenTelemetry.getMeter();
 *   private static final GaugeLong gauge =
 *       meter
 *           .gaugeLongBuilder("processed_jobs")
 *           .setDescription("Processed jobs")
 *           .setUnit("1")
 *           .setLabelKeys(Collections.singletonList("Key"))
 *           .build();
 *   // It is recommended to keep a reference of a Handle.
 *   private static final GaugeLong.Handle inboundHandle =
 *       gauge.getHandle(Collections.singletonList("SomeWork"));
 *    private static final GaugeLong.Handle defaultHandle = gauge.getDefaultHandle();
 *
 *   void doDefault() {
 *      // Your code here.
 *      defaultHandle.add(10);
 *   }
 *
 *   void doSomeWork() {
 *      // Your code here.
 *      inboundHandle.set(15);
 *   }
 *
 * }
 * }</pre>
 *
 * @since 0.1.0
 */
@ThreadSafe
public interface GaugeLong extends Metric<Handle> {

  @Override
  Handle getHandle(List<String> labelValues);

  @Override
  Handle getDefaultHandle();

  /**
   * A {@code Handle} for a {@code GaugeLong}.
   *
   * @since 0.1.0
   */
  interface Handle {

    /**
     * Adds the given value to the current value. The values can be negative.
     *
     * @param amt the value to add
     * @since 0.1.0
     */
    void add(long amt);

    /**
     * Sets the given value.
     *
     * @param val the new value.
     * @since 0.1.0
     */
    void set(long val);
  }

  /** Builder class for {@link GaugeLong}. */
  interface Builder extends Metric.Builder<Builder, GaugeLong> {}
}
