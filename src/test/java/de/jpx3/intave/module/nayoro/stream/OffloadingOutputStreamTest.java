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

package de.jpx3.intave.module.nayoro.stream;

import ac.intave.samples.event.Event;
import ac.intave.samples.event.MarkerEvent;
import ac.intave.samples.event.PropertiesEvent;
import ac.intave.samples.serial.JsonReader;
import ac.intave.samples.serial.JsonWriter;
import ac.intave.samples.serial.ZstdStreams;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

final class OffloadingOutputStreamTest {
  @Test
  void writesReachTargetInOrderOnceExecutorRuns() throws Exception {
    RecordingOutputStream target = new RecordingOutputStream();
    Queue<Runnable> tasks = new ArrayDeque<>();
    OffloadingOutputStream stream = new OffloadingOutputStream(target, tasks::add, 4, Long.MAX_VALUE);

    stream.write("abcdefghij".getBytes(StandardCharsets.US_ASCII));
    assertEquals("", target.content());

    runAll(tasks);
    assertEquals("abcdefgh", target.content());

    stream.flush();
    runAll(tasks);
    assertEquals("abcdefghij", target.content());
    assertEquals(Collections.singletonList(10), target.flushedAt);
  }

  @Test
  void closeWritesPendingChunksAndClosesTargetWithoutExecutor() throws Exception {
    RecordingOutputStream target = new RecordingOutputStream();
    OffloadingOutputStream stream = new OffloadingOutputStream(target, task -> {}, 3, Long.MAX_VALUE);

    stream.write("hello".getBytes(StandardCharsets.US_ASCII));
    stream.flush();
    stream.write(' ');
    stream.write("world".getBytes(StandardCharsets.US_ASCII));
    stream.close();

    assertEquals("hello world", target.content());
    assertEquals(Collections.singletonList(5), target.flushedAt);
    assertTrue(target.closed);
    assertThrows(IOException.class, () -> stream.write(1));
  }

  @Test
  void tasksRunAfterCloseDoNotTouchTarget() throws Exception {
    RecordingOutputStream target = new RecordingOutputStream();
    Queue<Runnable> tasks = new ArrayDeque<>();
    OffloadingOutputStream stream = new OffloadingOutputStream(target, tasks::add, 2, Long.MAX_VALUE);

    stream.write("abcd".getBytes(StandardCharsets.US_ASCII));
    stream.close();
    runAll(tasks);

    assertEquals("abcd", target.content());
    assertEquals(1, target.closeCalls);
  }

  @Test
  void catchesUpOnCallingThreadWhenTooMuchIsPending() throws Exception {
    RecordingOutputStream target = new RecordingOutputStream();
    OffloadingOutputStream stream = new OffloadingOutputStream(target, task -> {}, 4, 8);

    stream.write("12345678".getBytes(StandardCharsets.US_ASCII));
    assertEquals("", target.content());
    stream.write("9".getBytes(StandardCharsets.US_ASCII));
    stream.write("abc".getBytes(StandardCharsets.US_ASCII));

    // Only the oldest chunk is written here, enough to get back under the limit
    assertEquals("1234", target.content());

    stream.close();
    assertEquals("123456789abc", target.content());
  }

  @Test
  void keepsOrderWhenCallerAndExecutorDrainConcurrently() throws Exception {
    byte[] input = new byte[512 * 1024];
    new java.util.Random(7).nextBytes(input);
    ByteArrayOutputStream target = new ByteArrayOutputStream();
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      OffloadingOutputStream stream = new OffloadingOutputStream(target, executor, 1024, 4096);
      java.util.Random sizes = new java.util.Random(11);
      int offset = 0;
      while (offset < input.length) {
        int length = Math.min(input.length - offset, 1 + sizes.nextInt(3000));
        stream.write(input, offset, length);
        offset += length;
        if (sizes.nextInt(10) == 0) {
          stream.flush();
        }
      }
      stream.close();
    } finally {
      executor.shutdown();
      assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
    }
    assertArrayEquals(input, target.toByteArray());
  }

  @Test
  void reportsTargetFailureToWriter() throws Exception {
    OutputStream failing = new OutputStream() {
      @Override
      public void write(int value) throws IOException {
        throw new IOException("broken");
      }
    };
    Queue<Runnable> tasks = new ArrayDeque<>();
    OffloadingOutputStream stream = new OffloadingOutputStream(failing, tasks::add, 2, Long.MAX_VALUE);

    stream.write(new byte[]{1, 2});
    runAll(tasks);

    assertThrows(IOException.class, () -> stream.write(3));
    assertThrows(IOException.class, stream::close);
  }

  @Test
  void compressedRecordingMatchesDirectJsonWriter() throws Exception {
    List<Event> events = new ArrayList<>();
    for (int index = 0; index < 2000; index++) {
      MarkerEvent marker = new MarkerEvent(new UUID(index, index * 31L));
      marker.withOffset(index);
      events.add(marker);
      events.add(new PropertiesEvent(Collections.singletonMap("key" + index, index % 2 == 0)));
    }

    ByteArrayOutputStream direct = new ByteArrayOutputStream();
    try (JsonWriter writer = new JsonWriter(direct)) {
      for (Event event : events) {
        event.accept(writer);
      }
    }

    ExecutorService executor = Executors.newSingleThreadExecutor();
    ByteArrayOutputStream offloaded = new ByteArrayOutputStream();
    try {
      OutputStream stream = new OffloadingOutputStream(
        ZstdStreams.compressionStream(offloaded), executor, 1024, Long.MAX_VALUE
      );
      try (JsonWriter writer = new JsonWriter(
        new BufferedWriter(new OutputStreamWriter(stream, StandardCharsets.UTF_8))
      )) {
        for (Event event : events) {
          event.accept(writer);
        }
      }
    } finally {
      executor.shutdown();
      assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
    }

    assertEquals(decode(direct.toByteArray()), decode(offloaded.toByteArray()));
  }

  private static List<String> decode(byte[] recording) throws IOException {
    List<String> lines = new ArrayList<>();
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(
      ZstdStreams.decompressionStream(new ByteArrayInputStream(recording)), StandardCharsets.UTF_8
    ))) {
      String line;
      while ((line = reader.readLine()) != null) {
        lines.add(line);
      }
    }
    try (JsonReader reader = new JsonReader(new ByteArrayInputStream(recording))) {
      int decoded = 0;
      while (reader.nextEvent() != null) {
        decoded++;
      }
      assertEquals(lines.size(), decoded);
    }
    return lines;
  }

  private static void runAll(Queue<Runnable> tasks) {
    Runnable task;
    while ((task = tasks.poll()) != null) {
      task.run();
    }
  }

  private static final class RecordingOutputStream extends OutputStream {
    private final ByteArrayOutputStream content = new ByteArrayOutputStream();
    private final List<Integer> flushedAt = new ArrayList<>();
    private boolean closed;
    private int closeCalls;

    @Override
    public void write(int value) {
      content.write(value);
    }

    @Override
    public void write(byte[] bytes, int offset, int length) {
      content.write(bytes, offset, length);
    }

    @Override
    public void flush() {
      flushedAt.add(content.size());
    }

    @Override
    public void close() {
      closed = true;
      closeCalls++;
    }

    String content() {
      return new String(content.toByteArray(), StandardCharsets.US_ASCII);
    }
  }
}
