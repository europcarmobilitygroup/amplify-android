/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.amplifyframework.datastore.syncengine;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.test.core.app.ApplicationProvider;

import com.amplifyframework.core.async.Cancelable;
import com.amplifyframework.core.model.Model;
import com.amplifyframework.core.model.ModelProvider;
import com.amplifyframework.core.model.SchemaRegistry;
import com.amplifyframework.core.model.query.Where;
import com.amplifyframework.core.model.query.predicate.QueryPredicates;
import com.amplifyframework.core.model.temporal.Temporal;
import com.amplifyframework.datastore.DataStoreConfiguration;
import com.amplifyframework.datastore.DataStoreException;
import com.amplifyframework.datastore.appsync.ModelMetadata;
import com.amplifyframework.datastore.appsync.ModelWithMetadata;
import com.amplifyframework.datastore.storage.StorageItemChange;
import com.amplifyframework.datastore.storage.sqlite.SQLiteStorageAdapter;
import com.amplifyframework.datastore.storage.sqlite.migrations.MigrationConfiguration;
import com.amplifyframework.testmodels.commentsblog.AmplifyModelProvider;
import com.amplifyframework.testmodels.commentsblog.BlogOwner;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.observers.TestObserver;
import io.reactivex.rxjava3.schedulers.Schedulers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Race-condition tests for the {@link Merger} against local DataStore API writes, using the real
 * {@link SQLiteStorageAdapter} (Robolectric SQLite) and the real {@link PersistentMutationOutbox}.
 *
 * The bug: the merger checks "is there a pending mutation for this model?" and then writes the
 * remote model. A DataStore API save writes its row and then enqueues its pending mutation. When these
 * two sequences interleave, the merger overwrites a newer local row with older remote data, while a
 * pending mutation still exists. The fix makes both sequences atomic under one storage write lock.
 *
 * Invariant checked by every test: if a local save happened, the row holds the local data and its
 * pending mutation is in the outbox -- regardless of how it interleaved with a merge.
 *
 * The tests also guard against deadlock: every wait is bounded, and a hang fails the test.
 */
@RunWith(RobolectricTestRunner.class)
public final class MergerRaceConditionTest {
    private static final String DATABASE_NAME = "AmplifyDatastore.db";
    private static final long TIMEOUT_MS = TimeUnit.SECONDS.toMillis(5);
    // How long we wait to prove that an operation is BLOCKED by the lock (i.e., did not complete).
    private static final long BLOCKED_PROBE_MS = 300;
    private static final int STRESS_ITERATIONS = 100;

    private Context context;
    private SQLiteStorageAdapter storage;
    private PersistentMutationOutbox outbox;
    private StorageObserver storageObserver;
    private VersionRepository versionRepository;

    /**
     * Creates a fresh SQLite-backed storage adapter, a persistent outbox, and starts the storage
     * observer, which registers the local change interceptor (DataStore API saves -> outbox).
     * @throws Exception On setup failure
     */
    @Before
    public void setup() throws Exception {
        context = ApplicationProvider.getApplicationContext();
        context.deleteDatabase(DATABASE_NAME);

        ModelProvider modelProvider = AmplifyModelProvider.getInstance();
        SchemaRegistry schemaRegistry = SchemaRegistry.instance();
        schemaRegistry.clear();
        schemaRegistry.register(modelProvider.models());

        storage = SQLiteStorageAdapter.forModels(
            schemaRegistry, modelProvider, 1, new MigrationConfiguration.Builder().build());
        CountDownLatch initialized = new CountDownLatch(1);
        AtomicReference<DataStoreException> initFailure = new AtomicReference<>();
        storage.initialize(context, schemas -> initialized.countDown(), failure -> {
            initFailure.set(failure);
            initialized.countDown();
        }, DataStoreConfiguration.defaults());
        assertTrue("Storage adapter did not initialize.", initialized.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        if (initFailure.get() != null) {
            throw initFailure.get();
        }

        outbox = new PersistentMutationOutbox(storage);
        assertTrue(outbox.load().blockingAwait(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        storageObserver = new StorageObserver(storage, outbox);
        storageObserver.startObservingStorageChanges(() -> { }, () -> { });
        versionRepository = new VersionRepository(storage);
    }

    /**
     * Stops observation, terminates the adapter and deletes the database.
     * @throws DataStoreException On failure to terminate the adapter
     */
    @After
    public void tearDown() throws DataStoreException {
        if (storageObserver != null) {
            storageObserver.stopObservingStorageChanges();
        }
        if (storage != null) {
            storage.terminate();
        }
        context.deleteDatabase(DATABASE_NAME);
    }

    /**
     * Control case: with no pending mutation, the merger must apply the remote model.
     * Also checks that sync-engine writes (the seed) never create pending mutations.
     * @throws Exception On failure
     */
    @Test
    public void mergeWithoutPendingMutationAppliesRemoteModel() throws Exception {
        BlogOwner synced = seedSyncedOwner("synced-v1");
        assertFalse(hasPendingMutation(synced));

        BlogOwner remote = synced.copyOfBuilder().name("remote-v2").build();
        awaitMerge(merger(outbox), remote, 2);

        assertEquals("remote-v2", queryOwner(synced.getId()).getName());
        assertEquals(Integer.valueOf(2), metadataVersion(synced));
    }

    /**
     * Sequential version of the bug: a stale remote model (e.g. the subscription echo of the previous
     * mutation) arrives while a local change is pending. The merger must keep the local row and only
     * update the metadata.
     * @throws Exception On failure
     */
    @Test
    public void mergeWithPendingMutationKeepsLocalRowAndUpdatesMetadata() throws Exception {
        BlogOwner synced = seedSyncedOwner("synced-v1");
        BlogOwner local = synced.copyOfBuilder().name("local-new").build();
        appSave(local);
        assertTrue(hasPendingMutation(local));

        BlogOwner staleEcho = synced.copyOfBuilder().name("remote-echo-old").build();
        awaitMerge(merger(outbox), staleEcho, 2);

        assertEquals("local-new", queryOwner(local.getId()).getName());
        assertEquals(Integer.valueOf(2), metadataVersion(local));
        assertTrue(hasPendingMutation(local));
    }

    /**
     * Interleaving 1 (the race from the field): the merger has already checked "no pending mutation"
     * and is about to write the remote model, when the app saves.
     * The merger is paused INSIDE its critical section (in hasPendingMutation). The app save must be
     * blocked until the merger finishes, and must win afterwards.
     *
     * Before the fix, the app save completed during the pause and was then overwritten by the merger:
     * this test fails on the "app save was not blocked" assertion, and on the final row check.
     * @throws Exception On failure
     */
    @Test
    public void appSaveDuringMergerCriticalSectionIsNotOverwritten() throws Exception {
        BlogOwner synced = seedSyncedOwner("synced-v1");
        GatedOutbox gatedOutbox = new GatedOutbox(outbox);

        // 1. Merger starts and pauses right after checking for a pending mutation (none yet).
        BlogOwner remote = synced.copyOfBuilder().name("remote-old").build();
        TestObserver<Void> merge = merger(gatedOutbox).merge(withMetadata(remote, 2))
            .subscribeOn(Schedulers.io())
            .test();
        assertTrue("Merger never reached its pending-mutation check.",
            gatedOutbox.entered.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));

        // 2. App saves while the merger is paused in the (former) race window.
        BlogOwner local = synced.copyOfBuilder().name("local-new").build();
        List<Throwable> saveErrors = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch saved = appSaveAsync(local, saveErrors);

        // 3. The save must not be able to complete while the merger holds the write lock.
        assertFalse("App save completed inside the merger's critical section.",
            saved.await(BLOCKED_PROBE_MS, TimeUnit.MILLISECONDS));

        // 4. Release the merger. Both must finish (no deadlock).
        gatedOutbox.release.countDown();
        assertTrue("Merge did not complete.", merge.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        merge.assertNoErrors().assertComplete();
        assertTrue("App save did not complete.", saved.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        assertTrue(saveErrors.toString(), saveErrors.isEmpty());

        // 5. Invariant: the local data won, and its mutation is pending.
        assertEquals("local-new", queryOwner(local.getId()).getName());
        assertTrue(hasPendingMutation(local));
    }

    /**
     * Interleaving 2: the app save has written its row but has not enqueued its pending mutation yet,
     * when a merge arrives. The merge must wait until the pending mutation is enqueued, then see it and
     * keep the local row.
     * @throws Exception On failure
     */
    @Test
    public void mergeBetweenAppRowWriteAndEnqueueSeesPendingMutation() throws Exception {
        BlogOwner synced = seedSyncedOwner("synced-v1");

        // Replace the interceptor with one that pauses after the row write, before the enqueue.
        CountDownLatch rowWritten = new CountDownLatch(1);
        CountDownLatch releaseEnqueue = new CountDownLatch(1);
        storage.setLocalChangeInterceptor(change -> {
            rowWritten.countDown();
            awaitOrFail(releaseEnqueue, "enqueue release");
            outbox.enqueueSynchronously(toPendingMutation(change));
        });

        // 1. App save writes its row and pauses before enqueueing.
        BlogOwner local = synced.copyOfBuilder().name("local-new").build();
        List<Throwable> saveErrors = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch saved = appSaveAsync(local, saveErrors);
        assertTrue("App save never wrote its row.", rowWritten.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));

        // 2. A merge arrives. It must be blocked until the save has enqueued.
        BlogOwner remote = synced.copyOfBuilder().name("remote-old").build();
        TestObserver<Void> merge = merger(outbox).merge(withMetadata(remote, 2))
            .subscribeOn(Schedulers.io())
            .test();
        assertFalse("Merge completed while the app save was between row write and enqueue.",
            merge.await(BLOCKED_PROBE_MS, TimeUnit.MILLISECONDS));

        // 3. Release the save. Both must finish.
        releaseEnqueue.countDown();
        assertTrue("App save did not complete.", saved.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        assertTrue(saveErrors.toString(), saveErrors.isEmpty());
        assertTrue("Merge did not complete.", merge.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        merge.assertNoErrors().assertComplete();

        // 4. Invariant.
        assertEquals("local-new", queryOwner(local.getId()).getName());
        assertTrue(hasPendingMutation(local));
        assertEquals(Integer.valueOf(2), metadataVersion(local));
    }

    /**
     * Stress: app save and merge of the same model start at the same moment, many times, with random
     * jitter. Whatever the order, the row must end with the local data and a pending mutation:
     * - save first: the merger sees the pending mutation and keeps the local row;
     * - merge first: the merger writes the remote model, then the save overwrites it and enqueues.
     * Each iteration is bounded by a timeout, so a deadlock fails the test instead of hanging.
     * @throws Exception On failure
     */
    @Test
    public void concurrentAppSaveAndMergeAlwaysKeepLocalData() throws Exception {
        Merger merger = merger(outbox);
        Random random = new Random(42);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int iteration = 0; iteration < STRESS_ITERATIONS; iteration++) {
                BlogOwner synced = seedSyncedOwner("synced-" + iteration);
                BlogOwner local = synced.copyOfBuilder().name("local-" + iteration).build();
                BlogOwner remote = synced.copyOfBuilder().name("remote-" + iteration).build();
                int saveJitterMicros = random.nextInt(2000);
                int mergeJitterMicros = random.nextInt(2000);

                CountDownLatch start = new CountDownLatch(1);
                Future<?> saveTask = executor.submit(() -> {
                    awaitOrFail(start, "start");
                    sleepMicros(saveJitterMicros);
                    appSave(local);
                    return null;
                });
                Future<?> mergeTask = executor.submit(() -> {
                    awaitOrFail(start, "start");
                    sleepMicros(mergeJitterMicros);
                    awaitMerge(merger, remote, 2);
                    return null;
                });
                start.countDown();
                saveTask.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
                mergeTask.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);

                assertEquals("Iteration " + iteration + ": local data was overwritten.",
                    "local-" + iteration, queryOwner(local.getId()).getName());
                assertTrue("Iteration " + iteration + ": no pending mutation.", hasPendingMutation(local));
            }
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Deadlock guard: observers must never run while the write lock is held. An observer of a
     * sync-engine write (running on the sync-engine thread) performs a blocking DataStore API save,
     * which needs the write lock on the DataStore thread. If observers were notified under the lock,
     * this would deadlock.
     * @throws Exception On failure
     */
    @Test
    public void observerDoingBlockingSaveDuringMergeDoesNotDeadlock() throws Exception {
        BlogOwner synced = seedSyncedOwner("synced-v1");
        BlogOwner other = BlogOwner.builder().name("saved-from-observer").build();
        AtomicBoolean triggered = new AtomicBoolean(false);
        AtomicBoolean nestedSaveCompleted = new AtomicBoolean(false);
        Cancelable observation = storage.observe(change -> {
            boolean isMergedOwner = StorageItemChange.Initiator.SYNC_ENGINE.equals(change.initiator())
                && change.item() instanceof BlogOwner
                && synced.getId().equals(change.item().getId());
            if (isMergedOwner && triggered.compareAndSet(false, true)) {
                CountDownLatch nestedSave = appSaveAsync(other, new ArrayList<>());
                try {
                    nestedSaveCompleted.set(nestedSave.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }, failure -> { }, () -> { });
        try {
            BlogOwner remote = synced.copyOfBuilder().name("remote-v2").build();
            awaitMerge(merger(outbox), remote, 2);
            assertTrue("Observer was not notified of the merged model.", triggered.get());
            assertTrue("Blocking save inside an observer deadlocked.", nestedSaveCompleted.get());
            assertEquals("saved-from-observer", queryOwner(other.getId()).getName());
        } finally {
            observation.cancel();
        }
    }

    /**
     * Deadlock guard for the enqueue path: deleting a model whose CREATE is still pending (not in
     * flight) removes that CREATE from the outbox. That removal happens inside enqueue, under the write
     * lock, so it must be synchronous; an asynchronous delete on the sync-engine executor would
     * deadlock against a concurrent merge.
     * @throws Exception On failure
     */
    @Test
    public void deleteOverPendingCreateCompletesWithoutDeadlock() throws Exception {
        BlogOwner created = BlogOwner.builder().name("created-locally").build();
        appSave(created);
        assertTrue(hasPendingMutation(created));

        // Keep the sync-engine thread busy with merges of another model at the same time.
        BlogOwner synced = seedSyncedOwner("synced-v1");
        Merger merger = merger(outbox);
        TestObserver<Void> merges = Observable.range(2, 20)
            .concatMapCompletable(version -> merger.merge(
                withMetadata(synced.copyOfBuilder().name("remote-v" + version).build(), version)))
            .subscribeOn(Schedulers.io())
            .test();

        CountDownLatch deleted = new CountDownLatch(1);
        List<Throwable> deleteErrors = Collections.synchronizedList(new ArrayList<>());
        storage.delete(created, StorageItemChange.Initiator.DATA_STORE_API, QueryPredicates.all(),
            change -> deleted.countDown(),
            failure -> {
                deleteErrors.add(failure);
                deleted.countDown();
            });

        assertTrue("Delete over pending create deadlocked.", deleted.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        assertTrue(deleteErrors.toString(), deleteErrors.isEmpty());
        assertFalse("Pending CREATE should have been removed.", hasPendingMutation(created));
        assertTrue("Merges did not complete.", merges.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        merges.assertNoErrors().assertComplete();
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private Merger merger(MutationOutbox mutationOutbox) {
        return new Merger(mutationOutbox, versionRepository, storage);
    }

    // A model as if previously synced from the server: row + metadata v1, no pending mutation.
    private BlogOwner seedSyncedOwner(String name) throws DataStoreException {
        BlogOwner owner = BlogOwner.builder().name(name).build();
        storage.saveInternal(owner, StorageItemChange.Initiator.SYNC_ENGINE, QueryPredicates.all());
        storage.saveInternal(
            new ModelMetadata(owner.getModelName() + "|" + owner.getId(), false, 1, new Temporal.Timestamp()),
            StorageItemChange.Initiator.SYNC_ENGINE,
            QueryPredicates.all()
        );
        return owner;
    }

    private static <T extends Model> ModelWithMetadata<T> withMetadata(T model, int version) {
        // ModelWithMetadata normalizes the metadata id to "<ModelName>|<id>".
        return new ModelWithMetadata<>(model, new ModelMetadata(model.getId(), false, version, new Temporal.Timestamp()));
    }

    private static void awaitMerge(Merger merger, BlogOwner remote, int version) {
        assertTrue("Merge did not complete.",
            merger.merge(withMetadata(remote, version)).blockingAwait(TIMEOUT_MS, TimeUnit.MILLISECONDS));
    }

    private CountDownLatch appSaveAsync(BlogOwner owner, List<Throwable> errors) {
        CountDownLatch done = new CountDownLatch(1);
        storage.save(owner, StorageItemChange.Initiator.DATA_STORE_API, QueryPredicates.all(),
            change -> done.countDown(),
            failure -> {
                errors.add(failure);
                done.countDown();
            });
        return done;
    }

    private void appSave(BlogOwner owner) throws InterruptedException {
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        assertTrue("App save did not complete.",
            appSaveAsync(owner, errors).await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        assertTrue(errors.toString(), errors.isEmpty());
    }

    private boolean hasPendingMutation(BlogOwner owner) {
        return outbox.hasPendingMutation(owner.getId(), owner.getModelName());
    }

    @NonNull
    private BlogOwner queryOwner(String id) throws InterruptedException {
        AtomicReference<BlogOwner> result = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        storage.query(BlogOwner.class, StorageItemChange.Initiator.DATA_STORE_API, Where.id(id),
            results -> {
                if (results.hasNext()) {
                    result.set(results.next());
                }
                done.countDown();
            },
            failure -> done.countDown());
        assertTrue("Query did not complete.", done.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        assertNotNull("No BlogOwner with id " + id, result.get());
        return result.get();
    }

    @Nullable
    private Integer metadataVersion(BlogOwner owner) {
        return versionRepository.findModelVersion(owner).blockingGet();
    }

    private static PendingMutation<? extends Model> toPendingMutation(StorageItemChange<? extends Model> change) {
        switch (change.type()) {
            case CREATE:
                return PendingMutation.creation(change.patchItem(), change.modelSchema());
            case UPDATE:
                return PendingMutation.update(change.patchItem(), change.modelSchema(), change.predicate());
            case DELETE:
                return PendingMutation.deletion(change.patchItem(), change.modelSchema(), change.predicate());
            default:
                throw new IllegalStateException("Unknown change type " + change.type());
        }
    }

    private static void awaitOrFail(CountDownLatch latch, String what) {
        try {
            if (!latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                throw new AssertionError("Timed out waiting for " + what);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted waiting for " + what, interrupted);
        }
    }

    private static void sleepMicros(int micros) {
        long deadline = System.nanoTime() + TimeUnit.MICROSECONDS.toNanos(micros);
        while (System.nanoTime() < deadline) {
            Thread.yield();
        }
    }

    /**
     * Delegates to a real outbox, but blocks the FIRST call to {@link #hasPendingMutation} until
     * released. The Merger calls it inside its critical section, between the pending check and the
     * write, i.e. exactly in the window where the race used to happen.
     */
    private static final class GatedOutbox implements MutationOutbox {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicBoolean armed = new AtomicBoolean(true);
        private final MutationOutbox delegate;

        GatedOutbox(MutationOutbox delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean hasPendingMutation(@NonNull String modelId, @NonNull String modelName) {
            boolean result = delegate.hasPendingMutation(modelId, modelName);
            if (armed.compareAndSet(true, false)) {
                entered.countDown();
                awaitOrFail(release, "gate release");
            }
            return result;
        }

        @NonNull
        @Override
        public Completable load() {
            return delegate.load();
        }

        @NonNull
        @Override
        public <T extends Model> Completable enqueue(@NonNull PendingMutation<T> incomingMutation) {
            return delegate.enqueue(incomingMutation);
        }

        @Override
        public <T extends Model> void enqueueSynchronously(@NonNull PendingMutation<T> incomingMutation)
                throws DataStoreException {
            delegate.enqueueSynchronously(incomingMutation);
        }

        @NonNull
        @Override
        public Completable remove(@NonNull TimeBasedUuid pendingMutationId) {
            return delegate.remove(pendingMutationId);
        }

        @Nullable
        @Override
        public PendingMutation<? extends Model> peek() {
            return delegate.peek();
        }

        @Override
        public Completable markInFlight(@NonNull TimeBasedUuid pendingMutationId) {
            return delegate.markInFlight(pendingMutationId);
        }

        @NonNull
        @Override
        public Observable<OutboxEvent> events() {
            return delegate.events();
        }
    }
}
