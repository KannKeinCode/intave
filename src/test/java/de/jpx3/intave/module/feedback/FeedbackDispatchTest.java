package de.jpx3.intave.module.feedback;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class FeedbackDispatchTest {
  @Test
  void sendsImmediatelyWhenIdle() {
    FeedbackDispatch dispatch = new FeedbackDispatch();
    List<String> sent = new ArrayList<>();
    dispatch.send(() -> sent.add("ping"));
    assertEquals(Arrays.asList("ping"), sent);
  }

  @Test
  void defersNestedSendsUntilTheCurrentSendReturns() {
    // Paper drains queued packets inside a send; their listeners request feedback again.
    FeedbackDispatch dispatch = new FeedbackDispatch();
    List<String> sent = new ArrayList<>();
    dispatch.send(() -> {
      dispatch.send(() -> sent.add("second"));
      dispatch.send(() -> sent.add("third"));
      sent.add("first");
    });
    assertEquals(Arrays.asList("first", "second", "third"), sent);
  }

  @Test
  void aDrainTriggeringFeedbackPerQueuedPacketKeepsTheStackFlat() {
    // The crash: every drained packet requested feedback, whose send drained the next packet.
    FeedbackDispatch dispatch = new FeedbackDispatch();
    int queuedPackets = 100_000;
    int[] remaining = {queuedPackets};
    int[] sends = {0};
    int[] maxDepth = {0};
    int[] depth = {0};
    Runnable[] drainingSend = new Runnable[1];
    drainingSend[0] = () -> {
      depth[0]++;
      maxDepth[0] = Math.max(maxDepth[0], depth[0]);
      sends[0]++;
      if (remaining[0]-- > 0) {
        dispatch.send(drainingSend[0]);
      }
      depth[0]--;
    };
    dispatch.send(drainingSend[0]);
    assertEquals(queuedPackets + 1, sends[0]);
    assertEquals(1, maxDepth[0]);
  }

  @Test
  void aFailingSendDoesNotDropTheDeferredOnes() {
    FeedbackDispatch dispatch = new FeedbackDispatch();
    List<String> sent = new ArrayList<>();
    IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
      dispatch.send(() -> {
        dispatch.send(() -> sent.add("deferred"));
        throw new IllegalStateException("send failed");
      })
    );
    assertEquals("send failed", failure.getMessage());
    assertEquals(Arrays.asList("deferred"), sent);
  }

  @Test
  void laterFailuresAreKeptAsSuppressed() {
    FeedbackDispatch dispatch = new FeedbackDispatch();
    IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
      dispatch.send(() -> {
        dispatch.send(() -> {
          throw new IllegalArgumentException("deferred failed");
        });
        throw new IllegalStateException("send failed");
      })
    );
    assertEquals(1, failure.getSuppressed().length);
    assertEquals("deferred failed", failure.getSuppressed()[0].getMessage());
  }

  @Test
  void sendsImmediatelyAgainAfterAFailure() {
    FeedbackDispatch dispatch = new FeedbackDispatch();
    assertThrows(IllegalStateException.class, () -> dispatch.send(() -> {
      throw new IllegalStateException();
    }));
    List<String> sent = new ArrayList<>();
    dispatch.send(() -> sent.add("ping"));
    assertEquals(Arrays.asList("ping"), sent);
  }
}
