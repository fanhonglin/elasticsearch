/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */
package org.elasticsearch.xpack.searchablesnapshots;

import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.common.Priority;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.Index;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.indices.recovery.RecoveryState;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.snapshots.mockstore.MockRepository;
import org.elasticsearch.test.ESIntegTestCase;
import org.elasticsearch.threadpool.ThreadPool;
import org.hamcrest.Matchers;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;

@ESIntegTestCase.ClusterScope(scope = ESIntegTestCase.Scope.TEST, numDataNodes = 0)
public class SearchableSnapshotsRelocationIntegTests extends BaseSearchableSnapshotsIntegTestCase {

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return org.elasticsearch.common.collect.List.of(LocalStateSearchableSnapshots.class, MockRepository.Plugin.class);
    }

    public void testRelocationWaitsForPreWarm() throws Exception {
        internalCluster().startMasterOnlyNode();
        final String firstDataNode = internalCluster().startDataOnlyNode();
        final String index = "test-idx";
        createIndexWithContent(index, indexSettingsNoReplicas(1).build());
        final String repoName = "test-repo";
        createRepository(repoName, "mock");
        final String snapshotName = "test-snapshot";
        createSnapshot(repoName, snapshotName, org.elasticsearch.common.collect.List.of(index));
        assertAcked(client().admin().indices().prepareDelete(index));
        final String restoredIndex = mountSnapshot(repoName, snapshotName, index, Settings.EMPTY);
        ensureGreen(restoredIndex);
        final String secondDataNode = internalCluster().startDataOnlyNode();

        final ThreadPool threadPool = internalCluster().getInstance(ThreadPool.class, secondDataNode);
        final int preWarmThreads = threadPool.info(SearchableSnapshotsConstants.CACHE_PREWARMING_THREAD_POOL_NAME).getMax();
        final Executor executor = threadPool.executor(SearchableSnapshotsConstants.CACHE_PREWARMING_THREAD_POOL_NAME);
        final CyclicBarrier barrier = new CyclicBarrier(preWarmThreads + 1);
        final CountDownLatch latch = new CountDownLatch(1);
        for (int i = 0; i < preWarmThreads; i++) {
            executor.execute(() -> {
                try {
                    barrier.await();
                    latch.await();
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
        }
        logger.info("--> waiting for prewarm threads to all become blocked");
        barrier.await();

        logger.info("--> force index [{}] to relocate to [{}]", index, secondDataNode);
        assertAcked(
            client().admin()
                .indices()
                .prepareUpdateSettings(restoredIndex)
                .setSettings(
                    Settings.builder()
                        .put(
                            IndexMetadata.INDEX_ROUTING_REQUIRE_GROUP_SETTING.getConcreteSettingForNamespace("_name").getKey(),
                            secondDataNode
                        )
                )
        );
        assertBusy(() -> {
            final List<RecoveryState> recoveryStates = getActiveRelocations(restoredIndex);
            assertThat(recoveryStates, Matchers.hasSize(1));
            final RecoveryState shardRecoveryState = recoveryStates.get(0);
            assertEquals(firstDataNode, shardRecoveryState.getSourceNode().getName());
            assertEquals(secondDataNode, shardRecoveryState.getTargetNode().getName());
        });

        assertBusy(() -> assertSame(RecoveryState.Stage.TRANSLOG, getActiveRelocations(restoredIndex).get(0).getStage()));
        final Index restoredIdx = clusterAdmin().prepareState().get().getState().metadata().index(restoredIndex).getIndex();
        final IndicesService indicesService = internalCluster().getInstance(IndicesService.class, secondDataNode);
        assertEquals(1, indicesService.indexService(restoredIdx).getShard(0).outstandingCleanFilesConditions());
        final ClusterState state = client().admin().cluster().prepareState().get().getState();
        final String primaryNodeId = state.routingTable().index(restoredIndex).shard(0).primaryShard().currentNodeId();
        final DiscoveryNode primaryNode = state.nodes().resolveNode(primaryNodeId);
        assertEquals(firstDataNode, primaryNode.getName());

        logger.info("--> unblocking prewarm threads");
        latch.countDown();

        assertFalse(
            client().admin()
                .cluster()
                .prepareHealth(restoredIndex)
                .setWaitForNoRelocatingShards(true)
                .setWaitForEvents(Priority.LANGUID)
                .get()
                .isTimedOut()
        );
        assertBusy(() -> assertThat(getActiveRelocations(restoredIndex), Matchers.empty()));
    }

    private static List<RecoveryState> getActiveRelocations(String restoredIndex) {
        return client().admin()
            .indices()
            .prepareRecoveries(restoredIndex)
            .setDetailed(true)
            .setActiveOnly(true)
            .get()
            .shardRecoveryStates()
            .get(restoredIndex)
            .stream()
            // filter for relocations that are not in stage FINALIZE (they could end up in this stage without progress for good if the
            // target node does not have enough cache space available to hold the primary completely
            .filter(recoveryState -> recoveryState.getSourceNode() != null && recoveryState.getStage() != RecoveryState.Stage.FINALIZE)
            .collect(Collectors.toList());
    }
}
