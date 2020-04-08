/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.0. You may not use this file
 * except in compliance with the Zeebe Community License 1.0.
 */
package io.zeebe.logstreams.state;

import io.zeebe.db.ZeebeDb;
import io.zeebe.db.ZeebeDbFactory;
import io.zeebe.logstreams.impl.Loggers;
import io.zeebe.logstreams.spi.SnapshotController;
import io.zeebe.util.FileUtil;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.slf4j.Logger;

/** Controls how snapshot/recovery operations are performed */
public class StateSnapshotController implements SnapshotController {
  private static final Logger LOG = Loggers.SNAPSHOT_LOGGER;

  private final SnapshotStorage storage;
  private final ZeebeDbFactory zeebeDbFactory;
  private final ReplicationController replicationController;
  private ZeebeDb db;

  public StateSnapshotController(
      final ZeebeDbFactory rocksDbFactory, final SnapshotStorage storage) {
    this(rocksDbFactory, storage, new NoneSnapshotReplication());
  }

  public StateSnapshotController(
      final ZeebeDbFactory zeebeDbFactory,
      final SnapshotStorage storage,
      final SnapshotReplication replication) {
    this.storage = storage;
    this.zeebeDbFactory = zeebeDbFactory;
    this.replicationController = new ReplicationController(replication, storage);
  }

  @Override
  public Optional<Snapshot> takeSnapshot(final long lowerBoundSnapshotPosition) {
    final var optionalSnapshot = storage.getPendingSnapshotFor(lowerBoundSnapshotPosition);
    return optionalSnapshot.flatMap(this::createCommittedSnapshot);
  }

  @Override
  public Optional<Snapshot> takeTempSnapshot(final long lowerBoundSnapshotPosition) {
    final var optionalSnapshot = storage.getPendingSnapshotFor(lowerBoundSnapshotPosition);
    optionalSnapshot.ifPresent(this::createSnapshot);
    return optionalSnapshot;
  }

  @Override
  public void commitSnapshot(final Snapshot snapshot) {
    storage.commitSnapshot(snapshot);
  }

  @Override
  public void replicateLatestSnapshot(final Consumer<Runnable> executor) {
    final var optionalLatest = storage.getLatestSnapshot();
    if (optionalLatest.isPresent()) {
      final var latestSnapshotDirectory = optionalLatest.get().getPath();
      LOG.debug("Start replicating latest snapshot {}", latestSnapshotDirectory);

      try (final var stream = Files.list(latestSnapshotDirectory)) {
        final ByteArrayOutputStream outStream = new ByteArrayOutputStream();
        final var paths = stream.sorted().collect(Collectors.toList());
        for (final var path : paths) {
          outStream.write(Files.readAllBytes(path));
        }

        final long snapshotChecksum = SnapshotChunkUtil.createChecksum(outStream.toByteArray());

        for (final var path : paths) {
          executor.accept(
              () -> {
                LOG.debug("Replicate snapshot chunk {}", path);
                replicationController.replicate(
                    latestSnapshotDirectory.getFileName().toString(),
                    paths.size(),
                    path.toFile(),
                    snapshotChecksum);
              });
        }
      } catch (final IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }

  @Override
  public void consumeReplicatedSnapshots() {
    replicationController.consumeReplicatedSnapshots();
  }

  @Override
  public void recover() throws Exception {
    final var runtimeDirectory = storage.getRuntimeDirectory();

    if (Files.exists(runtimeDirectory)) {
      FileUtil.deleteFolder(runtimeDirectory);
    }

    final var snapshots =
        storage.getSnapshots().sorted(Comparator.reverseOrder()).collect(Collectors.toList());
    LOG.debug("Available snapshots: {}", snapshots);

    final var snapshotIterator = snapshots.iterator();
    boolean recoveredFromSnapshot = false;
    while (snapshotIterator.hasNext() && !recoveredFromSnapshot) {
      final var snapshot = snapshotIterator.next();

      FileUtil.copySnapshot(runtimeDirectory, snapshot.getPath());

      try {
        // open database to verify that the snapshot is recoverable
        openDb();
        LOG.debug("Recovered state from snapshot '{}'", snapshot);
        recoveredFromSnapshot = true;
      } catch (final Exception e) {
        FileUtil.deleteFolder(runtimeDirectory);

        if (snapshotIterator.hasNext()) {
          LOG.warn(
              "Failed to open snapshot '{}'. Delete this snapshot and try the previous one.",
              snapshot,
              e);
          FileUtil.deleteFolder(snapshot.getPath());
        } else {
          LOG.error(
              "Failed to open snapshot '{}'. No snapshots available to recover from. Manual action is required.",
              snapshot,
              e);
          throw new IllegalStateException("Failed to recover from snapshots", e);
        }
      }
    }
  }

  @Override
  public ZeebeDb openDb() {
    if (db == null) {
      final var runtimeDirectory = storage.getRuntimeDirectory();
      db = zeebeDbFactory.createDb(runtimeDirectory.toFile());
      LOG.debug("Opened database from '{}'.", runtimeDirectory);
    }

    return db;
  }

  @Override
  public int getValidSnapshotsCount() {
    return (int) storage.getSnapshots().count();
  }

  @Override
  public File getLastValidSnapshotDirectory() {
    return storage.getLatestSnapshot().map(Snapshot::getPath).map(Path::toFile).orElse(null);
  }

  @Override
  public void close() throws Exception {
    if (db != null) {
      db.close();
      final var runtimeDirectory = storage.getRuntimeDirectory();
      LOG.debug("Closed database from '{}'.", runtimeDirectory);
      db = null;
    }
  }

  boolean isDbOpened() {
    return db != null;
  }

  private Optional<? extends Snapshot> createCommittedSnapshot(final Snapshot snapshot) {
    if (!createSnapshot(snapshot)) {
      return Optional.empty();
    }

    return storage.commitSnapshot(snapshot);
  }

  private boolean createSnapshot(final Snapshot snapshot) {
    final var snapshotDir = snapshot.getPath();
    final var start = System.currentTimeMillis();

    if (db == null) {
      LOG.error("Expected to take a snapshot, but no database was opened");
      return false;
    }

    LOG.debug("Taking temporary snapshot into {}.", snapshotDir);
    try {
      db.createSnapshot(snapshotDir.toFile());
    } catch (final Exception e) {
      LOG.error("Failed to create snapshot of runtime database", e);
      return false;
    }

    final var elapsedSeconds = System.currentTimeMillis() - start;
    storage.getMetrics().observeSnapshotOperation(elapsedSeconds);

    return true;
  }
}
