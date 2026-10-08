/*
 * Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
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

import androidx.annotation.NonNull;

import com.amplifyframework.core.Action;
import com.amplifyframework.core.Amplify;
import com.amplifyframework.core.model.Model;
import com.amplifyframework.core.model.SerializedModel;
import com.amplifyframework.datastore.storage.LocalStorageAdapter;
import com.amplifyframework.datastore.storage.StorageItemChange;
import com.amplifyframework.logging.Logger;

import java.util.Objects;

import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.CompositeDisposable;

/**
 * Observes a {@link LocalStorageAdapter} for its {@link StorageItemChange}s.
 * When such a change is observed, build an {@link PendingMutation}, and write
 * it onto a {@link MutationOutbox}.
 */
final class StorageObserver {
    private static final Logger LOG = Amplify.Logging.forNamespace("amplify:aws-datastore");

    private final LocalStorageAdapter localStorageAdapter;
    private final MutationOutbox mutationOutbox;
    private final CompositeDisposable ongoingOperationsDisposable;

    StorageObserver(
            @NonNull LocalStorageAdapter localStorageAdapter,
            @NonNull MutationOutbox mutationOutbox) {
        this.localStorageAdapter = Objects.requireNonNull(localStorageAdapter);
        this.mutationOutbox = Objects.requireNonNull(mutationOutbox);
        this.ongoingOperationsDisposable = new CompositeDisposable();
    }

    /**
     * Start enqueuing local changes (those not caused by the sync engine) into the mutation outbox.
     *
     * The enqueue happens synchronously inside the storage adapter, while it still holds its write lock,
     * via a {@link LocalStorageAdapter.LocalChangeInterceptor}. The new row and its pending mutation therefore
     * become visible atomically, which the {@link Merger} relies on: it checks for a pending mutation and
     * writes the remote model under the same lock. (Previously the enqueue ran later, in an observer of the
     * storage change, which left a window where the row was updated but no mutation was pending yet.)
     *
     * The storage observation below is kept only to detect the adapter's termination.
     */
    void startObservingStorageChanges(Action onStarted, Action onStopped) {
        localStorageAdapter.setLocalChangeInterceptor(
            change -> mutationOutbox.enqueueSynchronously(toPendingMutation(change))
        );
        ongoingOperationsDisposable.add(
            Observable.<StorageItemChange<? extends Model>>create(emitter -> {
                localStorageAdapter.observe(emitter::onNext, emitter::onError, emitter::onComplete);
                onStarted.call();
            })
            .doOnSubscribe(disposable ->
                LOG.info("Now observing local storage. Local changes will be enqueued to mutation outbox.")
            )
            .ignoreElements()
            .subscribe(
                () -> {
                    LOG.warn("Storage adapter subscription terminated with completion.");
                    localStorageAdapter.setLocalChangeInterceptor(null);
                    onStopped.call();
                },
                error -> {
                    LOG.error("Storage adapter subscription ended in error", error);
                    localStorageAdapter.setLocalChangeInterceptor(null);
                    onStopped.call();
                }
            )
        );
    }

    private PendingMutation<SerializedModel> toPendingMutation(StorageItemChange<? extends Model> change) {
        switch (change.type()) {
            case CREATE:
                return PendingMutation.creation(change.patchItem(), change.modelSchema());
            case UPDATE:
                return PendingMutation.update(change.patchItem(), change.modelSchema(), change.predicate());
            case DELETE:
                return PendingMutation.deletion(change.patchItem(), change.modelSchema(), change.predicate());
            default:
                throw new IllegalStateException("Unknown mutation type = " + change.type());
        }
    }

    /**
     * Stop observing changes in the storage adapter.
     */
    void stopObservingStorageChanges() {
        localStorageAdapter.setLocalChangeInterceptor(null);
        ongoingOperationsDisposable.clear();
    }
}
