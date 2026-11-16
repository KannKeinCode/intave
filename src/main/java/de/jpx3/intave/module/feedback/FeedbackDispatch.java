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

package de.jpx3.intave.module.feedback;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Serializes the network sends of one player's feedback packets, so a send never re-enters
 * another send in progress.
 * <p>
 * Sending a packet can dispatch further outbound packets synchronously: when the player's
 * connection still holds queued packets, Paper appends the new packet and drains the queue in
 * place, running every outbound listener of the drained packets on the sending thread. Listeners
 * that request feedback for a drained packet would send again, which drains again, one stack
 * level per queued packet, until the stack overflows. A send requested while another one is in
 * progress is therefore deferred and performed right after it, in request order.
 * <p>
 * Not thread-safe by itself: callers must hold the player's feedback lock, so only the thread
 * performing the outermost send can see a send in progress.
 */
public final class FeedbackDispatch {
  private final Deque<Runnable> deferred = new ArrayDeque<>();
  private boolean sending;

  /**
   * Performs the send now, or right after the send in progress, keeping the request order. Sends
   * deferred while this one runs are performed before it returns, even if one of them fails.
   */
  public void send(Runnable send) {
    if (sending) {
      deferred.addLast(send);
      return;
    }
    sending = true;
    RuntimeException failure = null;
    try {
      send.run();
    } catch (RuntimeException exception) {
      failure = exception;
    }
    try {
      Runnable next;
      while ((next = deferred.pollFirst()) != null) {
        try {
          next.run();
        } catch (RuntimeException exception) {
          if (failure == null) {
            failure = exception;
          } else {
            failure.addSuppressed(exception);
          }
        }
      }
    } finally {
      sending = false;
    }
    if (failure != null) {
      throw failure;
    }
  }
}
