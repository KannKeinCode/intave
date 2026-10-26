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

package de.jpx3.intave.module.nayoro;

import ac.intave.samples.event.*;
import ac.intave.samples.serial.JsonWriter;
import ac.intave.samples.share.BlockUpdate;
import ac.intave.samples.share.Classifier;
import de.jpx3.intave.adapter.MinecraftVersion;
import de.jpx3.intave.module.nayoro.stream.PeriodicFlushOutputStream;
import de.jpx3.intave.module.tracker.entity.Entity;
import de.jpx3.intave.user.meta.MovementMetadata;
import de.jpx3.intave.version.ProtocolVersionConverter;

import java.io.IOException;
import java.io.OutputStream;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

final class RecordEventSink extends EventSink {
  private static final int COMPRESSION_FLUSH_THRESHOLD = 128 * 1024;
  // Serialize and compress events on a background thread to avoid blocking the server thread
  private static final ExecutorService ASYNC_SERIALIZER =
    Executors.newSingleThreadExecutor(r -> {
      Thread t = new Thread(r, "Intave-Nayoro-Writer");
      t.setDaemon(true);
      return t;
    });

  private final long startedAt = System.currentTimeMillis();
  private long lastEventAt = startedAt;
  private final Environment environment;
  private final OutputStream output;
  private JsonWriter writer;
  private final Set<Integer> entities = new HashSet<>();
  private final NearbyBlockTracker nearbyBlocks = new NearbyBlockTracker();
  private boolean setup = false;
  // Guarded by writeLock so an in-flight event either finishes before close or observes closure.
  private volatile boolean closed = false;
  private final Classifier classifier;
  private final Lock writeLock = new ReentrantLock();

  public RecordEventSink(Environment environment, OutputStream output) {
    this.environment = environment;
    this.output = output;
    this.classifier = Classifier.UNKNOWN;
  }

  public RecordEventSink(Environment environment, OutputStream output, Classifier classifier) {
    this.environment = environment;
    this.output = output;
    this.classifier = classifier == null ? Classifier.UNKNOWN : classifier;
  }

  public synchronized void setupIfNeeded() {
    if (!setup) {
      try {
        writeLock.lock();
        JsonWriter initializedWriter = new JsonWriter(
          new PeriodicFlushOutputStream(output, COMPRESSION_FLUSH_THRESHOLD)
        );
        HeaderEvent headerEvent = new HeaderEvent(UUID.randomUUID(), "unknown", classifier, startedAt);
        writer = initializedWriter;
        setup = true;
        ASYNC_SERIALIZER.execute(() -> {
          writeLock.lock();
          try {
            if (!closed) {
              initializedWriter.visitAny(headerEvent);
            }
          } finally {
            writeLock.unlock();
          }
        });
      } catch (IOException exception) {
        throw new IllegalStateException("Could not initialize recording writer", exception);
      } finally {
        writeLock.unlock();
      }
      PlayerContainer player = environment.mainPlayer();
      int serverVersion = ProtocolVersionConverter.protocolVersionBy(MinecraftVersion.current());
      visit(new PlayerInitEvent(
        player.name(), player.uuid(), player.id(), player.version(),
        serverVersion,
        SampleTypes.position(player.position()), SampleTypes.rotation(player.rotation()),
        player.flying()
      ));
      visit(new PropertiesEvent(environment.properties()));
      environment.mainPlayer().applyIfUserPresent(user -> {
        for (Entity tracedEntity : user.meta().connection().tracedEntities()) {
          visit(new EntitySpawnEvent(
            null, tracedEntity.entityId(), tracedEntity.entityName(),
            SampleTypes.hitboxSize(tracedEntity.typeData().size()),
            null,
            SampleTypes.position(tracedEntity.position.toPosition())
          ));
        }
      });
    }
  }

  @Override
  public void visit(EntitySpawnEvent event) {
    entities.add(event.id());
    visitAny(event);
  }

  @Override
  public void visit(AttackEvent event) {
    if (isIdInContextCurrent(event.source()) && isIdInContextCurrent(event.target())) {
      visitAny(event);
    }
  }

  private boolean isIdInContextCurrent(int id) {
    return entities.contains(id) || environment.mainPlayer().id() == id;
  }

  @Override
  public void visit(EntityMoveEvent event) {
    if (entities.contains(event.entityId())) {
      visitAny(event);
    }
  }

  @Override
  public synchronized void visit(PlayerMoveEvent event) {
    environment.mainPlayer().applyIfUserPresent(user -> {
      MovementMetadata movement = user.meta().movement();
      double dx = movement.positionX() - movement.lastPositionX();
      double dy = movement.positionY() - movement.lastPositionY();
      double dz = movement.positionZ() - movement.lastPositionZ();
      List<BlockUpdate> updates = nearbyBlocks.dirtyNearbyBlocks(
        user.blockCache(), movement.boundingBox(), movement.lookVector(),
        Math.sqrt(dx * dx + dy * dy + dz * dz)
      );
      if (!updates.isEmpty()) {
        visitAny(new BlockUpdatesEvent(updates));
      }
    });
    visitAny(event);
  }

  @Override
  public void visit(EntityRemoveEvent event) {
    if (entities.remove(event.id())) {
      visitAny(event);
    }
  }

  @Override
  public synchronized void visitAny(Event event) {
    if (closed) {
      return;
    }
    setupIfNeeded();
    long now = System.currentTimeMillis();
    event.withOffset(Math.max(0, now - lastEventAt));
    lastEventAt = now;
    ASYNC_SERIALIZER.execute(() -> {
      writeLock.lock();
      try {
        if (closed || writer == null) {
          return;
        }
        writer.visitAny(event);
      } finally {
        writeLock.unlock();
      }
    });
  }

  @Override
  public synchronized void close() {
    if (closed) {
      return;
    }
    setupIfNeeded();
    try {
      ASYNC_SERIALIZER.submit(() -> {
        writeLock.lock();
        try {
          if (closed) {
            return;
          }
          closed = true;
          if (writer != null) {
            writer.close();
          }
        } finally {
          writeLock.unlock();
        }
      }).get();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
    } catch (ExecutionException exception) {
      Throwable cause = exception.getCause();
      if (cause instanceof RuntimeException) {
        throw (RuntimeException) cause;
      }
      throw new IllegalStateException("Could not close recording writer", cause);
    }
  }

  @Override
  public String name() {
    return "RECORD";
  }

}
