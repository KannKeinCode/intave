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

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Buffers writes and passes them to the target stream on an executor, in order.
 * Not safe for concurrent writers. Close finishes the remaining writes on the calling thread.
 */
public final class OffloadingOutputStream extends OutputStream {
  private static final byte[] FLUSH = new byte[0];

  private final OutputStream target;
  private final Executor executor;
  private final long maxPendingBytes;
  private final byte[] buffer;
  private int position;

  private final Queue<byte[]> pending = new ConcurrentLinkedQueue<>();
  private final AtomicLong pendingBytes = new AtomicLong();
  // Guards the target so chunks reach it in queue order
  private final ReentrantLock targetLock = new ReentrantLock();
  private volatile IOException failure;
  private volatile boolean closed;

  public OffloadingOutputStream(OutputStream target, Executor executor, int chunkSize, long maxPendingBytes) {
    if (chunkSize <= 0) {
      throw new IllegalArgumentException("Chunk size must be positive");
    }
    this.target = target;
    this.executor = executor;
    this.buffer = new byte[chunkSize];
    this.maxPendingBytes = maxPendingBytes;
  }

  @Override
  public void write(int value) throws IOException {
    ensureWritable();
    buffer[position++] = (byte) value;
    if (position == buffer.length) {
      handOff();
    }
  }

  @Override
  public void write(byte @NotNull [] bytes, int offset, int length) throws IOException {
    if (offset < 0 || length < 0 || length > bytes.length - offset) {
      throw new IndexOutOfBoundsException();
    }
    ensureWritable();
    while (length > 0) {
      int copied = Math.min(length, buffer.length - position);
      System.arraycopy(bytes, offset, buffer, position, copied);
      position += copied;
      offset += copied;
      length -= copied;
      if (position == buffer.length) {
        handOff();
      }
    }
  }

  @Override
  public void flush() throws IOException {
    ensureWritable();
    if (position > 0) {
      pending.add(takeBuffer());
    }
    enqueue(FLUSH);
  }

  @Override
  public void close() throws IOException {
    if (closed) {
      return;
    }
    if (position > 0) {
      pending.add(takeBuffer());
    }
    targetLock.lock();
    try {
      closed = true;
      drainLocked(0);
      try {
        target.close();
      } catch (IOException exception) {
        if (failure == null) {
          failure = exception;
        }
      }
    } finally {
      targetLock.unlock();
    }
    IOException exception = failure;
    if (exception != null) {
      throw new IOException("Offloaded write failed", exception);
    }
  }

  private void ensureWritable() throws IOException {
    if (closed) {
      throw new IOException("Stream closed");
    }
    IOException exception = failure;
    if (exception != null) {
      throw new IOException("Offloaded write failed", exception);
    }
  }

  private void handOff() {
    enqueue(takeBuffer());
  }

  private byte[] takeBuffer() {
    byte[] chunk = Arrays.copyOf(buffer, position);
    position = 0;
    pendingBytes.addAndGet(chunk.length);
    return chunk;
  }

  private void enqueue(byte[] chunk) {
    pending.add(chunk);
    // Executor is stalled, write the oldest chunks here until we are back under the limit.
    // Skipped while a drain is running, that one is already making progress.
    if (pendingBytes.get() > maxPendingBytes && targetLock.tryLock()) {
      try {
        if (!closed) {
          drainLocked(maxPendingBytes);
        }
      } finally {
        targetLock.unlock();
      }
    }
    try {
      // One drain per chunk, so a dropped task can't strand chunks
      executor.execute(this::drain);
    } catch (RejectedExecutionException exception) {
      drain();
    }
  }

  private void drain() {
    targetLock.lock();
    try {
      if (!closed) {
        drainLocked(0);
      }
    } finally {
      targetLock.unlock();
    }
  }

  // Writes queued chunks in order until at most keepBytes are left, 0 drains everything
  private void drainLocked(long keepBytes) {
    byte[] chunk;
    while ((keepBytes == 0 || pendingBytes.get() > keepBytes) && (chunk = pending.poll()) != null) {
      pendingBytes.addAndGet(-chunk.length);
      if (failure != null) {
        continue;
      }
      try {
        if (chunk == FLUSH) {
          target.flush();
        } else {
          target.write(chunk);
        }
      } catch (IOException exception) {
        failure = exception;
      } catch (RuntimeException exception) {
        failure = new IOException(exception);
      }
    }
  }
}
