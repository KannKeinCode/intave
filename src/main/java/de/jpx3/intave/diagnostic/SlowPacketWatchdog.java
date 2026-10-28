/*
 * Copyright 2026 Intave
 *
 * This software is licensed under the PolyForm Perimeter License 1.0.0.
 * You may use this software for any purpose, except for providing to
 * others any product that competes with the software.
 *
 * A copy of the license is available at:
 *   https://polyformproject.org/licenses/perimeter/1.0.0/
 */

package de.jpx3.intave.diagnostic;

import com.comphenix.protocol.PacketType;
import de.jpx3.intave.IntaveLogger;
import de.jpx3.intave.user.User;
import de.jpx3.intave.user.meta.ConnectionMetadata;

import java.util.concurrent.TimeUnit;

/** Logs packets that take Intave unusually long to handle, at most once per player per interval. */
public final class SlowPacketWatchdog {
  private static final long THRESHOLD_NANOS = TimeUnit.MILLISECONDS.toNanos(50);
  private static final long REPORT_INTERVAL = TimeUnit.SECONDS.toMillis(10);

  private SlowPacketWatchdog() {
    throw new UnsupportedOperationException("Initialization of helper class");
  }

  public static void observe(User user, PacketType type, boolean incoming, long startNanos) {
    long duration = System.nanoTime() - startNanos;
    if (duration < THRESHOLD_NANOS || !user.hasPlayer()) {
      return;
    }
    ConnectionMetadata connection = user.meta().connection();
    long now = System.currentTimeMillis();
    if (now - connection.lastSlowPacketReport < REPORT_INTERVAL) {
      return;
    }
    connection.lastSlowPacketReport = now;
    IntaveLogger.logger().info(
      "Handling " + (incoming ? "incoming " : "outgoing ") + type.name()
        + " for " + user.player().getName()
        + " took " + TimeUnit.NANOSECONDS.toMillis(duration) + "ms"
        + " on " + Thread.currentThread().getName()
        + " (" + connection.feedbackSummary() + ")"
    );
  }
}
