/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.0. You may not use this file
 * except in compliance with the Zeebe Community License 1.0.
 */
package io.zeebe.logstreams.state;

import static org.assertj.core.api.Assertions.assertThat;

import io.zeebe.db.impl.DefaultColumnFamily;
import io.zeebe.db.impl.rocksdb.ZeebeRocksDbFactory;
import io.zeebe.logstreams.util.TestSnapshotStorage;
import io.zeebe.test.util.AutoCloseableRule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class FailingSnapshotChunkReplicationTest {

  @Rule public final TemporaryFolder tempFolderRule = new TemporaryFolder();
  @Rule public final AutoCloseableRule autoCloseableRule = new AutoCloseableRule();

  private StateSnapshotController replicatorSnapshotController;
  private StateSnapshotController receiverSnapshotController;
  private SnapshotStorage receiverStorage;
  private SnapshotStorage replicatorStorage;

  public void setup(final SnapshotReplication replicator) throws IOException {
    final var senderRoot = tempFolderRule.newFolder("sender");
    replicatorStorage = new TestSnapshotStorage(senderRoot.toPath());

    final var receiverRoot = tempFolderRule.newFolder("receiver");
    receiverStorage = new TestSnapshotStorage(receiverRoot.toPath());

    setupReplication(replicator);
  }

  private void setupReplication(final SnapshotReplication replicator) {
    replicatorSnapshotController =
        new StateSnapshotController(
            ZeebeRocksDbFactory.newFactory(DefaultColumnFamily.class),
            replicatorStorage,
            replicator);
    receiverSnapshotController =
        new StateSnapshotController(
            ZeebeRocksDbFactory.newFactory(DefaultColumnFamily.class), receiverStorage, replicator);

    autoCloseableRule.manage(replicatorSnapshotController);
    autoCloseableRule.manage(replicatorStorage);
    autoCloseableRule.manage(receiverSnapshotController);
    autoCloseableRule.manage(receiverStorage);
    replicatorSnapshotController.openDb();
  }

  @Test
  public void shouldNotWriteChunksAfterReceivingInvalidChunk() throws Exception {
    // given
    final EvilReplicator replicator = new EvilReplicator();
    setup(replicator);

    receiverSnapshotController.consumeReplicatedSnapshots();
    replicatorSnapshotController.takeSnapshot(1);

    // when
    replicatorSnapshotController.replicateLatestSnapshot(Runnable::run);

    // then
    final List<SnapshotChunk> replicatedChunks = replicator.replicatedChunks;
    assertThat(replicatedChunks.size()).isGreaterThan(0);

    final var snapshotDirectory = receiverStorage.getPendingDirectoryFor("1").orElseThrow();
    assertThat(snapshotDirectory).doesNotExist();
    assertThat(receiverStorage.exists("1")).isFalse();
  }

  @Test
  public void shouldNotMarkSnapshotAsValidIfNotReceivedAllChunks() throws Exception {
    // given
    final FlakyReplicator replicator = new FlakyReplicator();
    setup(replicator);

    receiverSnapshotController.consumeReplicatedSnapshots();
    replicatorSnapshotController.takeSnapshot(1);

    // when
    replicatorSnapshotController.replicateLatestSnapshot(Runnable::run);

    // then
    final List<SnapshotChunk> replicatedChunks = replicator.replicatedChunks;
    assertThat(replicatedChunks.size()).isGreaterThan(0);

    final var snapshotDirectory =
        receiverStorage
            .getPendingDirectoryFor(replicatedChunks.get(0).getSnapshotId())
            .orElseThrow();
    assertThat(snapshotDirectory).exists();
    final var files = Files.list(snapshotDirectory).collect(Collectors.toList());
    assertThat(files)
        .extracting(p -> p.getFileName().toString())
        .containsExactlyInAnyOrder(
            replicatedChunks.subList(0, 2).stream()
                .map(SnapshotChunk::getChunkName)
                .toArray(String[]::new));
    assertThat(receiverStorage.exists(replicatedChunks.get(0).getSnapshotId())).isFalse();
  }

  @Test
  public void shouldSnapshotWithWrongChecksum() throws Exception {
    // given
    final InterruptedReplicator replicator = new InterruptedReplicator();
    setup(replicator);

    receiverSnapshotController.consumeReplicatedSnapshots();
    replicatorSnapshotController.takeSnapshot(1);

    // when
    replicatorSnapshotController.replicateLatestSnapshot(Runnable::run);
    final List<SnapshotChunk> pendingChunks = new ArrayList<>(replicator.unsentSnapshots);

    final Path pendingSnapshot =
        receiverStorage.getPendingDirectoryFor(pendingChunks.get(0).getSnapshotId()).orElseThrow();
    final List<Path> snapshotChunks = Files.list(pendingSnapshot).collect(Collectors.toList());
    assertThat(snapshotChunks).hasSize(pendingChunks.get(0).getTotalCount() - 1);
    snapshotChunks.forEach(
        p -> {
          try {
            Files.delete(p);
          } catch (IOException e) {
            e.printStackTrace();
          }
        });

    replicator.count = 0;
    pendingChunks.forEach(replicator::replicate);

    // then
    assertThat(
            receiverStorage
                .getPendingDirectoryFor(pendingChunks.get(0).getSnapshotId())
                .orElseThrow())
        .doesNotExist();
  }

  private final class FlakyReplicator implements SnapshotReplication {

    final List<SnapshotChunk> replicatedChunks = new ArrayList<>();
    private Consumer<SnapshotChunk> chunkConsumer;

    @Override
    public void replicate(final SnapshotChunk snapshot) {
      replicatedChunks.add(snapshot);
      if (chunkConsumer != null) {
        if (replicatedChunks.size() < 3) {
          chunkConsumer.accept(snapshot);
        }
      }
    }

    @Override
    public void consume(final Consumer<SnapshotChunk> consumer) {
      chunkConsumer = consumer;
    }

    @Override
    public void close() {}
  }

  private final class InterruptedReplicator implements SnapshotReplication {
    final List<SnapshotChunk> unsentSnapshots = new ArrayList<>();
    int count = 0;
    private Consumer<SnapshotChunk> chunkConsumer;

    @Override
    public void replicate(final SnapshotChunk snapshot) {
      count++;

      if (count < snapshot.getTotalCount() && chunkConsumer != null) {
        chunkConsumer.accept(snapshot);
      } else {
        unsentSnapshots.add(snapshot);
      }
    }

    @Override
    public void consume(final Consumer<SnapshotChunk> consumer) {
      chunkConsumer = consumer;
    }

    @Override
    public void close() {}
  }

  private final class EvilReplicator implements SnapshotReplication {

    final List<SnapshotChunk> replicatedChunks = new ArrayList<>();
    private Consumer<SnapshotChunk> chunkConsumer;

    @Override
    public void replicate(final SnapshotChunk snapshot) {
      replicatedChunks.add(snapshot);
      if (chunkConsumer != null) {
        chunkConsumer.accept(
            replicatedChunks.size() > 1 ? new DisruptedSnapshotChunk(snapshot) : snapshot);
      }
    }

    @Override
    public void consume(final Consumer<SnapshotChunk> consumer) {
      chunkConsumer = consumer;
    }

    @Override
    public void close() {}
  }

  private final class DisruptedSnapshotChunk implements SnapshotChunk {
    private final SnapshotChunk snapshotChunk;

    DisruptedSnapshotChunk(final SnapshotChunk snapshotChunk) {
      this.snapshotChunk = snapshotChunk;
    }

    @Override
    public String getSnapshotId() {
      return snapshotChunk.getSnapshotId();
    }

    @Override
    public int getTotalCount() {
      return snapshotChunk.getTotalCount();
    }

    @Override
    public String getChunkName() {
      return snapshotChunk.getChunkName();
    }

    @Override
    public long getChecksum() {
      return 0;
    }

    @Override
    public byte[] getContent() {
      return snapshotChunk.getContent();
    }

    @Override
    public long getSnapshotChecksum() {
      return snapshotChunk.getSnapshotChecksum();
    }
  }
}
