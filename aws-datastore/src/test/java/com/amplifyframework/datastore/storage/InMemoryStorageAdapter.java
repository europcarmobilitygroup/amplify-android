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

package com.amplifyframework.datastore.storage;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.amplifyframework.AmplifyException;
import com.amplifyframework.core.Action;
import com.amplifyframework.core.Consumer;
import com.amplifyframework.core.async.Cancelable;
import com.amplifyframework.core.model.Model;
import com.amplifyframework.core.model.ModelSchema;
import com.amplifyframework.core.model.SerializedModel;
import com.amplifyframework.core.model.query.ObserveQueryOptions;
import com.amplifyframework.core.model.query.QueryOptions;
import com.amplifyframework.core.model.query.predicate.QueryPredicate;
import com.amplifyframework.datastore.DataStoreConfiguration;
import com.amplifyframework.datastore.DataStoreException;
import com.amplifyframework.datastore.DataStoreQuerySnapshot;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.subjects.PublishSubject;
import io.reactivex.rxjava3.subjects.Subject;

/**
 * A simple in-memory implementation of the LocalStorageAdapter
 * contract. This intended for use as a stub in test code.
 * Like the SQLite adapter, it serializes writes with a lock, supports
 * {@link #writeExclusively(ExclusiveWrite)}, and hands DataStore API changes to the
 * {@link LocalChangeInterceptor} (which is how the sync engine enqueues local changes).
 */
public final class InMemoryStorageAdapter implements LocalStorageAdapter {
    private final List<Model> items;
    private final Subject<StorageItemChange<? extends Model>> itemChangeStream;
    private final ReentrantLock writeLock;
    private volatile LocalChangeInterceptor localChangeInterceptor;

    private InMemoryStorageAdapter() {
        this.items = new ArrayList<>();
        this.itemChangeStream = PublishSubject.<StorageItemChange<? extends Model>>create().toSerialized();
        this.writeLock = new ReentrantLock(true);
    }

    /**
     * Creates an instance of an in-memory storage adapter.
     * @return An in-memory storage adapter
     */
    public static InMemoryStorageAdapter create() {
        return new InMemoryStorageAdapter();
    }

    @Override
    public void initialize(@NonNull Context context,
                            @NonNull Consumer<List<ModelSchema>> onSuccess,
                            @NonNull Consumer<DataStoreException> onError,
                            @NonNull DataStoreConfiguration dataStoreConfiguration) {

    }

    @Override
    public <T extends Model> void save(
            @NonNull final T item,
            @NonNull final StorageItemChange.Initiator initiator,
            @NonNull final QueryPredicate predicate,
            @NonNull final Consumer<StorageItemChange<T>> onSuccess,
            @NonNull final Consumer<DataStoreException> onError) {
        final StorageItemChange<T> change;
        try {
            change = saveInternal(item, initiator, predicate);
        } catch (DataStoreException failure) {
            onError.accept(failure);
            return;
        }
        onSuccess.accept(change);
    }

    @Override
    public <T extends Model> StorageItemChange<T> saveInternal(
            @NonNull final T item,
            @NonNull final StorageItemChange.Initiator initiator,
            @NonNull final QueryPredicate predicate) throws DataStoreException {
        writeLock.lock();
        try {
            StorageItemChange.Type type = StorageItemChange.Type.CREATE;
            final int index = indexOf(item);
            Model savedItem = null;
            if (index > -1) {
                // There is an existing record with that ID; this is an update.
                type = StorageItemChange.Type.UPDATE;
                savedItem = items.get(index);
                if (!predicate.evaluate(savedItem)) {
                    throw new DataStoreException(
                        "Conditional check failed.",
                        "Verify that there is a saved model that matches the provided predicate.");
                }
                items.remove(index);
            }
            final ModelSchema schema;
            final SerializedModel patchItem;
            try {
                schema = ModelSchema.fromModelClass(item.getClass());
                patchItem = SerializedModel.difference(item, savedItem, schema);
            } catch (AmplifyException schemaBuildFailure) {
                throw new DataStoreException(
                    "Failed to build model schema.", schemaBuildFailure, "Verify your model."
                );
            }
            items.add(item);
            StorageItemChange<T> change = StorageItemChange.<T>builder()
                .item(item)
                .patchItem(patchItem)
                .modelSchema(schema)
                .type(type)
                .predicate(predicate)
                .initiator(initiator)
                .build();
            publishAndIntercept(change);
            return change;
        } finally {
            writeLock.unlock();
        }
    }

    @SuppressWarnings("unchecked") // (T) item *is* checked, via isAssignableFrom().
    @Override
    public <T extends Model> void query(
            @NonNull final Class<T> itemClass,
            @NonNull final StorageItemChange.Initiator initiator,
            @NonNull final QueryOptions options,
            @NonNull final Consumer<Iterator<T>> onSuccess,
            @NonNull final Consumer<DataStoreException> onError
    ) {
        final List<T> result = new ArrayList<>();
        final QueryPredicate predicate = options.getQueryPredicate();
        writeLock.lock();
        try {
            for (Model item : items) {
                if (itemClass.isAssignableFrom(item.getClass()) && predicate.evaluate(item)) {
                    result.add((T) item);
                }
            }
        } finally {
            writeLock.unlock();
        }
        onSuccess.accept(result.iterator());
    }

    @Override
    public void query(
            @NonNull String modelName,
            @NonNull StorageItemChange.Initiator initiator,
            @NonNull QueryOptions options,
            @NonNull Consumer<Iterator<? extends Model>> onSuccess,
            @NonNull Consumer<DataStoreException> onError) {
        final List<Model> result = new ArrayList<>();
        final QueryPredicate predicate = options.getQueryPredicate();
        writeLock.lock();
        try {
            for (Model item : items) {
                if (modelName.equals(item.getClass().getSimpleName()) && predicate.evaluate(item)) {
                    result.add(item); //TODO, add tests for new query method.
                }
            }
        } finally {
            writeLock.unlock();
        }
        onSuccess.accept(result.iterator());
    }

    @Override
    public <T extends Model> void delete(
            @NonNull final T item,
            @NonNull final StorageItemChange.Initiator initiator,
            @NonNull final QueryPredicate predicate,
            @NonNull final Consumer<StorageItemChange<T>> onSuccess,
            @NonNull final Consumer<DataStoreException> onError
    ) {
        final StorageItemChange<T> deletion;
        try {
            deletion = deleteInternal(item, initiator, predicate);
        } catch (DataStoreException failure) {
            onError.accept(failure);
            return;
        }
        onSuccess.accept(deletion);
    }

    @SuppressWarnings("unchecked") // item.getClass() -> Class<?>, but type is T. So cast as Class<T> is OK.
    @Override
    public <T extends Model> StorageItemChange<T> deleteInternal(
            @NonNull final T item,
            @NonNull final StorageItemChange.Initiator initiator,
            @NonNull final QueryPredicate predicate
    ) throws DataStoreException {
        writeLock.lock();
        try {
            final int index = indexOf(item);
            if (index < 0) {
                throw new DataStoreException(
                    "This item was not found in the datastore: " + item.toString(),
                    "Use save() function to create models to store."
                );
            }
            Model savedItem = items.get(index);
            if (!predicate.evaluate(savedItem)) {
                throw new DataStoreException(
                    "Conditional check failed.",
                    "Verify that there is a saved model that matches the provided predicate.");
            }
            final ModelSchema schema;
            final SerializedModel patchItem;
            try {
                schema = ModelSchema.fromModelClass(item.getClass());
                patchItem = SerializedModel.create(savedItem, schema);
            } catch (AmplifyException schemaBuildFailure) {
                throw new DataStoreException(
                    "Failed to build model schema.", schemaBuildFailure, "Verify your model."
                );
            }
            items.remove(index);
            StorageItemChange<T> deletion = StorageItemChange.<T>builder()
                .item((T) savedItem)
                .patchItem(patchItem)
                .modelSchema(schema)
                .type(StorageItemChange.Type.DELETE)
                .predicate(predicate)
                .initiator(initiator)
                .build();
            publishAndIntercept(deletion);
            return deletion;
        } finally {
            writeLock.unlock();
        }
    }

    @SuppressWarnings("unchecked") // item.getClass() -> Class<?>, but type is T. So cast as Class<T> is OK.
    @Override
    public <T extends Model> void delete(
            @NonNull Class<T> itemClass,
            @NonNull StorageItemChange.Initiator initiator,
            @NonNull QueryPredicate predicate,
            @NonNull Action onSuccess,
            @NonNull Consumer<DataStoreException> onError
    ) {
        final ModelSchema schema;
        try {
            schema = ModelSchema.fromModelClass(itemClass);
        } catch (AmplifyException schemaBuildFailure) {
            onError.accept(new DataStoreException(
                    "Failed to build model schema.", schemaBuildFailure, "Verify your model."
            ));
            return;
        }

        writeLock.lock();
        try {
            Iterator<Model> iterator = items.iterator();
            while (iterator.hasNext()) {
                Model savedItem = iterator.next();
                if (!itemClass.isInstance(savedItem) || !predicate.evaluate(savedItem)) {
                    continue;
                }
                iterator.remove();

                StorageItemChange<T> deletion = StorageItemChange.<T>builder()
                        .item((T) savedItem)
                        .modelSchema(schema)
                        .type(StorageItemChange.Type.DELETE)
                        .predicate(predicate)
                        .initiator(initiator)
                        .build();
                publishAndIntercept(deletion);
            }
        } catch (DataStoreException failure) {
            onError.accept(failure);
            return;
        } finally {
            writeLock.unlock();
        }
        onSuccess.call();
    }

    @Override
    public void setLocalChangeInterceptor(@Nullable LocalChangeInterceptor interceptor) {
        this.localChangeInterceptor = interceptor;
    }

    @Override
    public void writeExclusively(@NonNull ExclusiveWrite write) throws DataStoreException {
        writeLock.lock();
        try {
            write.run();
        } finally {
            writeLock.unlock();
        }
    }

    @NonNull
    @Override
    public Cancelable observe(
            @NonNull Consumer<StorageItemChange<? extends Model>> onNextItem,
            @NonNull Consumer<DataStoreException> onSubscriptionError,
            @NonNull Action onSubscriptionComplete) {
        Disposable disposable = itemChangeStream.subscribe(
            onNextItem::accept,
            failure -> {
                if (failure instanceof DataStoreException) {
                    onSubscriptionError.accept((DataStoreException) failure);
                } else {
                    onSubscriptionError.accept(new DataStoreException(
                        "Failed to observe changes to in-memory storage adapter.",
                        failure, "Inspect the details."
                    ));
                }
            },
            onSubscriptionComplete::call
        );
        return disposable::dispose;
    }

    @Override
    public <T extends Model> void observeQuery(@NonNull Class<T> itemClass,
                                               @NonNull ObserveQueryOptions options,
                                               @NonNull Consumer<Cancelable> onObservationStarted,
                                               @NonNull Consumer<DataStoreQuerySnapshot<T>> onQuerySnapshot,
                                               @NonNull Consumer<DataStoreException> onObservationError,
                                               @NonNull Action onObservationComplete) {
    //TODOPM: to be implemented for tests.
    }

    @Override
    public void terminate() {
        items.clear();
        itemChangeStream.onComplete();
    }

    @Override
    public void clear(@NonNull Action onComplete,
                      @NonNull Consumer<DataStoreException> onError) {
        items.clear();
        onComplete.call();
    }

    // Emits the change (synchronously, as before) and, for DataStore API changes, hands it to the
    // local change interceptor while the write lock is still held -- mirroring SQLiteStorageAdapter.
    private void publishAndIntercept(StorageItemChange<? extends Model> change) throws DataStoreException {
        itemChangeStream.onNext(change);
        if (StorageItemChange.Initiator.DATA_STORE_API.equals(change.initiator())) {
            LocalChangeInterceptor interceptor = localChangeInterceptor;
            if (interceptor != null) {
                interceptor.onLocalChange(change);
            }
        }
    }

    private int indexOf(Model item) {
        int index = 0;
        for (Model savedItem : items) {
            if (savedItem.getClass().equals(item.getClass())
                    && savedItem.getId().equals(item.getId())) {
                return index;
            }
            index++;
        }
        return -1;
    }
}
