/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hudi.table.action.compact;

import org.apache.hudi.client.SparkRDDReadClient;
import org.apache.hudi.client.SparkRDDWriteClient;
import org.apache.hudi.client.WriteClientTestUtils;
import org.apache.hudi.common.config.HoodieMetadataConfig;
import org.apache.hudi.common.model.HoodieRecord;
import org.apache.hudi.common.table.HoodieTableMetaClient;
import org.apache.hudi.common.table.timeline.HoodieInstant;
import org.apache.hudi.common.table.timeline.HoodieTimeline;
import org.apache.hudi.config.HoodieCompactionConfig;
import org.apache.hudi.config.HoodieWriteConfig;
import org.apache.hudi.table.HoodieSparkTable;
import org.apache.hudi.table.marker.WriteMarkersFactory;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

public class TestInlineCompaction extends CompactionTestBase {

  private HoodieWriteConfig getConfigForInlineCompaction(int maxDeltaCommits, int maxDeltaTime, CompactionTriggerStrategy inlineCompactionType) {
    return getConfigBuilder(false)
        .withCompactionConfig(HoodieCompactionConfig.newBuilder()
            .withInlineCompaction(true)
            .withMaxNumDeltaCommitsBeforeCompaction(maxDeltaCommits)
            .withMaxDeltaSecondsBeforeCompaction(maxDeltaTime)
            .withInlineCompactionTriggerStrategy(inlineCompactionType).build())
        .build();
  }

  private HoodieWriteConfig getConfigDisableCompaction(int maxDeltaCommits, int maxDeltaTime, CompactionTriggerStrategy inlineCompactionType) {
    return getConfigBuilder(false)
          .withMetadataConfig(HoodieMetadataConfig.newBuilder().enable(false).build())
          .withCompactionConfig(HoodieCompactionConfig.newBuilder()
                .withInlineCompaction(false)
                .withScheduleInlineCompaction(false)
                .withMaxNumDeltaCommitsBeforeCompaction(maxDeltaCommits)
                .withMaxDeltaSecondsBeforeCompaction(maxDeltaTime)
                .withInlineCompactionTriggerStrategy(inlineCompactionType).build())
          .build();
  }

  private void waitForMs(long timeMs) {
    try {
      Thread.sleep(timeMs);
    } catch (InterruptedException e) {
      throw new RuntimeException("Interrupted while killing time", e);
    }
  }

  @Test
  public void testCompactionIsNotScheduledEarly() throws Exception {
    // Given: make two commits
    HoodieWriteConfig cfg = getConfigForInlineCompaction(3, 60, CompactionTriggerStrategy.NUM_COMMITS);
    try (SparkRDDWriteClient<?> writeClient = getHoodieWriteClient(cfg)) {
      List<HoodieRecord> records = dataGen.generateInserts(WriteClientTestUtils.createNewInstantTime(), 100);
      SparkRDDReadClient readClient = getHoodieReadClient(cfg.getBasePath());
      List<String> instants = IntStream.range(0, 2).mapToObj(i -> WriteClientTestUtils.createNewInstantTime()).collect(Collectors.toList());
      runNextDeltaCommits(writeClient, readClient, instants, records, cfg, true, new ArrayList<>());
      HoodieTableMetaClient metaClient = createMetaClient(cfg.getBasePath());

      // Then: ensure no compaction is executed since there are only 2 delta commits
      assertEquals(2, metaClient.getActiveTimeline().getWriteTimeline().countInstants());
    }
  }

  @Test
  public void testSuccessfulCompactionBasedOnNumCommits() throws Exception {
    // Given: make three commits
    HoodieWriteConfig cfg = getConfigForInlineCompaction(3, 60, CompactionTriggerStrategy.NUM_COMMITS);

    try (SparkRDDWriteClient<?> writeClient = getHoodieWriteClient(cfg)) {
      List<String> instants = IntStream.range(0, 2).mapToObj(i -> WriteClientTestUtils.createNewInstantTime()).collect(Collectors.toList());

      List<HoodieRecord> records = dataGen.generateInserts(instants.get(0), 100);
      SparkRDDReadClient readClient = getHoodieReadClient(cfg.getBasePath());
      runNextDeltaCommits(writeClient, readClient, instants, records, cfg, true, new ArrayList<>());

      // third commit, that will trigger compaction
      HoodieTableMetaClient metaClient = createMetaClient(cfg.getBasePath());
      String finalInstant = WriteClientTestUtils.createNewInstantTime();
      createNextDeltaCommit(finalInstant, dataGen.generateUpdates(finalInstant, 100), writeClient, metaClient, cfg, false);

      // Then: ensure the file slices are compacted as per policy
      metaClient = createMetaClient(cfg.getBasePath());
      assertEquals(4, metaClient.getActiveTimeline().getWriteTimeline().countInstants());
      assertEquals(HoodieTimeline.COMMIT_ACTION, metaClient.getActiveTimeline().lastInstant().get().getAction());
      String compactionTime = metaClient.getActiveTimeline().lastInstant().get().requestedTime();
      assertFalse(WriteMarkersFactory.get(cfg.getMarkersType(), HoodieSparkTable.create(cfg, context), compactionTime).doesMarkerDirExist());
    }
  }

  @Test
  public void testSuccessfulCompactionBasedOnNumAfterCompactionRequest() throws Exception {
    // Given: make 4 commits
    HoodieWriteConfig cfg = getConfigDisableCompaction(4, 60, CompactionTriggerStrategy.NUM_COMMITS_AFTER_LAST_REQUEST);
    // turn off compaction table service to mock compaction service is down or very slow
    try (SparkRDDWriteClient<?> writeClient = getHoodieWriteClient(cfg)) {
      List<String> instants = IntStream.range(0, 4).mapToObj(i -> WriteClientTestUtils.createNewInstantTime()).collect(Collectors.toList());

      List<HoodieRecord> records = dataGen.generateInserts(instants.get(0), 100);
      SparkRDDReadClient readClient = getHoodieReadClient(cfg.getBasePath());

      // step 1: create and complete 4 delta commit, then create 1 compaction request after this
      runNextDeltaCommits(writeClient, readClient, instants, records, cfg, true, new ArrayList<>());

      String requestInstant = WriteClientTestUtils.createNewInstantTime();
      scheduleCompaction(requestInstant, writeClient, cfg);

      metaClient = createMetaClient(cfg.getBasePath());
      assertEquals(metaClient.getActiveTimeline().getInstantsAsStream()
          .filter(hoodieInstant -> hoodieInstant.getAction().equals(HoodieTimeline.COMPACTION_ACTION)
              && hoodieInstant.getState() == HoodieInstant.State.REQUESTED).count(), 1);

      // step 2: try to create another, but this one should fail because the NUM_COMMITS_AFTER_LAST_REQUEST strategy ,
      // and will throw a AssertionError due to scheduleCompaction will check if the last instant is a compaction request
      requestInstant = WriteClientTestUtils.createNewInstantTime();
      try {
        scheduleCompaction(requestInstant, writeClient, cfg);
        Object fail = Assertions.fail();
      } catch (AssertionError error) {
        //should be here
      }

      // step 3: complete another 4 delta commit should be 2 compaction request after this
      instants = IntStream.range(0, 4).mapToObj(i -> WriteClientTestUtils.createNewInstantTime()).collect(Collectors.toList());
      records = dataGen.generateInsertsForPartition(instants.get(0), 100, "2022/03/15");
      for (String instant : instants) {
        createNextDeltaCommit(instant, records, writeClient, metaClient, cfg, false);
      }
      // runNextDeltaCommits(writeClient, readClient, instants, records, cfg, true, gotPendingCompactionInstants);
      requestInstant = WriteClientTestUtils.createNewInstantTime();
      scheduleCompaction(requestInstant, writeClient, cfg);

      // step 4: restore the table service, complete the last commit, and this commit will trigger all compaction requests
      cfg = getConfigForInlineCompaction(4, 60, CompactionTriggerStrategy.NUM_COMMITS_AFTER_LAST_REQUEST);
      try (SparkRDDWriteClient<?> newWriteClient = getHoodieWriteClient(cfg)) {
        String finalInstant = WriteClientTestUtils.createNewInstantTime();
        createNextDeltaCommit(finalInstant, dataGen.generateUpdates(finalInstant, 100), newWriteClient, metaClient, cfg, false);
      }

      metaClient = createMetaClient(cfg.getBasePath());
      // step 5: there should be only 2 .commit, and no pending compaction.
      // the last instant should be delta commit since the compaction request is earlier.
      assertEquals(metaClient.getActiveTimeline().getCommitsTimeline().filter(instant -> instant.getAction().equals(HoodieTimeline.COMMIT_ACTION))
            .countInstants(), 2);
      assertEquals(metaClient.getActiveTimeline().getCommitsTimeline().filterPendingCompactionTimeline().countInstants(), 0);
      assertEquals(HoodieTimeline.DELTA_COMMIT_ACTION, metaClient.getActiveTimeline().lastInstant().get().getAction());
    }
  }

  @Test
  public void testSuccessfulCompactionBasedOnTime() throws Exception {
    // Given: make one commit
    HoodieWriteConfig cfg = getConfigForInlineCompaction(5, 10, CompactionTriggerStrategy.TIME_ELAPSED);

    try (SparkRDDWriteClient<?> writeClient = getHoodieWriteClient(cfg)) {
      String instantTime = WriteClientTestUtils.createNewInstantTime();
      List<HoodieRecord> records = dataGen.generateInserts(instantTime, 10);
      SparkRDDReadClient readClient = getHoodieReadClient(cfg.getBasePath());
      runNextDeltaCommits(writeClient, readClient, Arrays.asList(instantTime), records, cfg, true, new ArrayList<>());

      // after 10s, that will trigger compaction
      waitForMs(10000);
      String finalInstant = WriteClientTestUtils.createNewInstantTime();
      HoodieTableMetaClient metaClient = createMetaClient(cfg.getBasePath());
      createNextDeltaCommit(finalInstant, dataGen.generateUpdates(finalInstant, 100), writeClient, metaClient, cfg, false);

      // Then: ensure the file slices are compacted as per policy
      metaClient = createMetaClient(cfg.getBasePath());
      assertEquals(3, metaClient.getActiveTimeline().getWriteTimeline().countInstants());
      assertEquals(HoodieTimeline.COMMIT_ACTION, metaClient.getActiveTimeline().lastInstant().get().getAction());
    }
  }

  @Test
  public void testSuccessfulCompactionBasedOnNumOrTime() throws Exception {
    // Given: make three commits
    HoodieWriteConfig cfg = getConfigForInlineCompaction(3, 60, CompactionTriggerStrategy.NUM_OR_TIME);
    try (SparkRDDWriteClient<?> writeClient = getHoodieWriteClient(cfg)) {
      List<HoodieRecord> records = dataGen.generateInserts(WriteClientTestUtils.createNewInstantTime(), 10);
      SparkRDDReadClient readClient = getHoodieReadClient(cfg.getBasePath());
      List<String> instants = IntStream.range(0, 2).mapToObj(i -> WriteClientTestUtils.createNewInstantTime()).collect(Collectors.toList());
      runNextDeltaCommits(writeClient, readClient, instants, records, cfg, true, new ArrayList<>());
      // Then: trigger the compaction because reach 3 commits.
      String finalInstant = WriteClientTestUtils.createNewInstantTime();
      HoodieTableMetaClient metaClient = createMetaClient(cfg.getBasePath());
      createNextDeltaCommit(finalInstant, dataGen.generateUpdates(finalInstant, 10), writeClient, metaClient, cfg, false);

      metaClient = createMetaClient(cfg.getBasePath());
      assertEquals(4, metaClient.getActiveTimeline().getWriteTimeline().countInstants());
      // 4th commit, that will trigger compaction because reach the time elapsed
      metaClient = createMetaClient(cfg.getBasePath());
      waitForMs(60000);
      finalInstant = WriteClientTestUtils.createNewInstantTime();
      createNextDeltaCommit(finalInstant, dataGen.generateUpdates(finalInstant, 10), writeClient, metaClient, cfg, false);

      metaClient = createMetaClient(cfg.getBasePath());
      assertEquals(6, metaClient.getActiveTimeline().getWriteTimeline().countInstants());
    }
  }

  @Test
  public void testSuccessfulCompactionBasedOnNumAndTime() throws Exception {
    // Given: make three commits
    HoodieWriteConfig cfg = getConfigForInlineCompaction(3, 20, CompactionTriggerStrategy.NUM_AND_TIME);
    try (SparkRDDWriteClient<?> writeClient = getHoodieWriteClient(cfg)) {
      List<HoodieRecord> records = dataGen.generateInserts(WriteClientTestUtils.createNewInstantTime(), 10);
      SparkRDDReadClient readClient = getHoodieReadClient(cfg.getBasePath());
      List<String> instants = IntStream.range(0, 2).mapToObj(i -> WriteClientTestUtils.createNewInstantTime()).collect(Collectors.toList());
      runNextDeltaCommits(writeClient, readClient, instants, records, cfg, true, new ArrayList<>());
      HoodieTableMetaClient metaClient = createMetaClient(cfg.getBasePath());

      // Then: ensure no compaction is executed since there are only 3 delta commits
      assertEquals(2, metaClient.getActiveTimeline().getWriteTimeline().countInstants());
      // 3d commit, that will trigger compaction
      metaClient = createMetaClient(cfg.getBasePath());
      waitForMs(20000);
      String finalInstant = WriteClientTestUtils.createNewInstantTime();
      createNextDeltaCommit(finalInstant, dataGen.generateUpdates(finalInstant, 10), writeClient, metaClient, cfg, false);

      metaClient = createMetaClient(cfg.getBasePath());
      assertEquals(4, metaClient.getActiveTimeline().getWriteTimeline().countInstants());
    }
  }

  @Test
  public void testCompactionRetryOnFailureBasedOnNumCommits() throws Exception {
    // Given: two commits, schedule compaction and its failed/in-flight
    HoodieWriteConfig cfg = getConfigBuilder(false)
        .withCompactionConfig(HoodieCompactionConfig.newBuilder()
            .withInlineCompaction(false)
            .withMaxNumDeltaCommitsBeforeCompaction(1).build())
        .build();
    String instantTime2;
    try (SparkRDDWriteClient<?> writeClient = getHoodieWriteClient(cfg)) {
      List<String> instants = IntStream.range(0, 2).mapToObj(i -> WriteClientTestUtils.createNewInstantTime()).collect(Collectors.toList());
      List<HoodieRecord> records = dataGen.generateInserts(instants.get(0), 100);
      SparkRDDReadClient readClient = getHoodieReadClient(cfg.getBasePath());
      runNextDeltaCommits(writeClient, readClient, instants, records, cfg, true, new ArrayList<>());
      // Schedule compaction instant2, make it in-flight (simulates inline compaction failing)
      instantTime2 = WriteClientTestUtils.createNewInstantTime();
      scheduleCompaction(instantTime2, writeClient, cfg);
      moveCompactionFromRequestedToInflight(instantTime2, cfg);
    }

    // When: a third commit happens
    HoodieWriteConfig inlineCfg = getConfigForInlineCompaction(2, 60, CompactionTriggerStrategy.NUM_COMMITS);
    try (SparkRDDWriteClient<?> writeClient = getHoodieWriteClient(inlineCfg)) {
      String instantTime3 = WriteClientTestUtils.createNewInstantTime();
      HoodieTableMetaClient metaClient = createMetaClient(cfg.getBasePath());
      createNextDeltaCommit(instantTime3, dataGen.generateUpdates(instantTime3, 100), writeClient, metaClient, inlineCfg, false);
    }

    // Then: 1 delta commit is done, the failed compaction is retried
    metaClient = createMetaClient(cfg.getBasePath());
    assertEquals(4, metaClient.getActiveTimeline().getWriteTimeline().countInstants());
    assertEquals(instantTime2, metaClient.getActiveTimeline().getCommitAndReplaceTimeline().filterCompletedInstants().firstInstant().get().requestedTime());
  }

  @Test
  public void testCompactionRetryOnFailureBasedOnTime() throws Exception {
    // Given: two commits, schedule compaction and its failed/in-flight
    HoodieWriteConfig cfg = getConfigBuilder(false)
        .withCompactionConfig(HoodieCompactionConfig.newBuilder()
            .withInlineCompaction(false)
            .withMaxDeltaSecondsBeforeCompaction(5)
            .withInlineCompactionTriggerStrategy(CompactionTriggerStrategy.TIME_ELAPSED).build())
        .build();
    String instantTime;
    try (SparkRDDWriteClient<?> writeClient = getHoodieWriteClient(cfg)) {
      List<String> instants = IntStream.range(0, 2).mapToObj(i -> WriteClientTestUtils.createNewInstantTime()).collect(Collectors.toList());
      List<HoodieRecord> records = dataGen.generateInserts(instants.get(0), 100);
      SparkRDDReadClient readClient = getHoodieReadClient(cfg.getBasePath());
      runNextDeltaCommits(writeClient, readClient, instants, records, cfg, true, new ArrayList<>());
      // Schedule compaction instantTime, make it in-flight (simulates inline compaction failing)
      waitForMs(10000);
      instantTime = WriteClientTestUtils.createNewInstantTime();
      scheduleCompaction(instantTime, writeClient, cfg);
      moveCompactionFromRequestedToInflight(instantTime, cfg);
    }

    // When: commit happens after 1000s. assumption is that, there won't be any new compaction getting scheduled within 100s, but the previous failed one will be
    // rolled back and retried to move it to completion.
    HoodieWriteConfig inlineCfg = getConfigForInlineCompaction(5, 1000, CompactionTriggerStrategy.TIME_ELAPSED);
    String instantTime2;
    try (SparkRDDWriteClient<?> writeClient = getHoodieWriteClient(inlineCfg)) {
      HoodieTableMetaClient metaClient = createMetaClient(cfg.getBasePath());
      instantTime2 = WriteClientTestUtils.createNewInstantTime();
      createNextDeltaCommit(instantTime2, dataGen.generateUpdates(instantTime2, 10), writeClient, metaClient, inlineCfg, false);
    }

    // Then: 1 delta commit is done, the failed compaction is retried
    metaClient = createMetaClient(cfg.getBasePath());
    // 2 delta commits at the beginning. 1 compaction, 1 delta commit following it.
    assertEquals(4, metaClient.getActiveTimeline().getWriteTimeline().countInstants());
    assertEquals(instantTime, metaClient.getActiveTimeline().getCommitAndReplaceTimeline().filterCompletedInstants().firstInstant().get().requestedTime());
  }

  @Test
  public void testCompactionRetryOnFailureBasedOnNumAndTime() throws Exception {
    // Given: two commits, schedule compaction and its failed/in-flight
    HoodieWriteConfig cfg = getConfigBuilder(false)
        .withCompactionConfig(HoodieCompactionConfig.newBuilder()
            .withInlineCompaction(false)
            .withMaxDeltaSecondsBeforeCompaction(1)
            .withMaxNumDeltaCommitsBeforeCompaction(1)
            .withInlineCompactionTriggerStrategy(CompactionTriggerStrategy.NUM_AND_TIME).build())
        .build();
    String instantTime;
    try (SparkRDDWriteClient<?> writeClient = getHoodieWriteClient(cfg)) {
      List<String> instants = IntStream.range(0, 2).mapToObj(i -> WriteClientTestUtils.createNewInstantTime()).collect(Collectors.toList());
      List<HoodieRecord> records = dataGen.generateInserts(instants.get(0), 10);
      SparkRDDReadClient readClient = getHoodieReadClient(cfg.getBasePath());
      runNextDeltaCommits(writeClient, readClient, instants, records, cfg, true, new ArrayList<>());
      // Schedule compaction instantTime, make it in-flight (simulates inline compaction failing)
      instantTime = WriteClientTestUtils.createNewInstantTime();
      scheduleCompaction(instantTime, writeClient, cfg);
      moveCompactionFromRequestedToInflight(instantTime, cfg);
    }

    // When: a third commit happens
    HoodieWriteConfig inlineCfg = getConfigForInlineCompaction(3, 20, CompactionTriggerStrategy.NUM_OR_TIME);
    String instantTime2;
    try (SparkRDDWriteClient<?> writeClient = getHoodieWriteClient(inlineCfg)) {
      HoodieTableMetaClient metaClient = createMetaClient(cfg.getBasePath());
      instantTime2 = WriteClientTestUtils.createNewInstantTime();
      createNextDeltaCommit(instantTime2, dataGen.generateUpdates(instantTime2, 10), writeClient, metaClient, inlineCfg, false);
    }

    // Then: 1 delta commit is done, the failed compaction is retried
    metaClient = createMetaClient(cfg.getBasePath());
    assertEquals(4, metaClient.getActiveTimeline().getWriteTimeline().countInstants());
    assertEquals(instantTime, metaClient.getActiveTimeline().getCommitAndReplaceTimeline().filterCompletedInstants().firstInstant().get().requestedTime());
  }
}
