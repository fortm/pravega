/**
 * Copyright (c) 2017 Dell Inc., or its subsidiaries. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.pravega.segmentstore.storage.mocks;

import com.google.common.base.Preconditions;
import io.pravega.common.Exceptions;
import io.pravega.common.concurrent.FutureHelpers;
import io.pravega.common.util.ArrayView;
import io.pravega.common.util.CloseableIterator;
import io.pravega.common.util.OrderedItemProcessor;
import io.pravega.common.util.SequencedItemList;
import io.pravega.segmentstore.storage.DataLogWriterNotPrimaryException;
import io.pravega.segmentstore.storage.DurableDataLog;
import io.pravega.segmentstore.storage.DurableDataLogException;
import io.pravega.segmentstore.storage.LogAddress;
import io.pravega.segmentstore.storage.QueueStats;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Duration;
import java.util.Iterator;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * In-Memory Mock for DurableDataLog. Contents is destroyed when object is garbage collected.
 */
@ThreadSafe
class InMemoryDurableDataLog implements DurableDataLog {
    static final Supplier<Duration> DEFAULT_APPEND_DELAY_PROVIDER = () -> Duration.ZERO; // No delay.
    private static final int DEFAULT_WRITE_CONCURRENCY = 10; // TODO: consider making configurable (if there's a need).
    private final EntryCollection entries;
    private final String clientId;
    private final ScheduledExecutorService executorService;
    private final Supplier<Duration> appendDelayProvider;
    private final OrderedItemProcessor<ArrayView, LogAddress> throttler;
    @GuardedBy("entries")
    private long offset;
    @GuardedBy("entries")
    private long epoch;
    private boolean closed;
    private boolean initialized;

    InMemoryDurableDataLog(EntryCollection entries, ScheduledExecutorService executorService) {
        this(entries, DEFAULT_APPEND_DELAY_PROVIDER, executorService);
    }

    InMemoryDurableDataLog(EntryCollection entries, Supplier<Duration> appendDelayProvider, ScheduledExecutorService executorService) {
        this.entries = Preconditions.checkNotNull(entries, "entries");
        this.appendDelayProvider = Preconditions.checkNotNull(appendDelayProvider, "appendDelayProvider");
        this.executorService = Preconditions.checkNotNull(executorService, "executorService");
        this.offset = Long.MIN_VALUE;
        this.epoch = Long.MIN_VALUE;
        this.clientId = UUID.randomUUID().toString();
        this.throttler = new OrderedItemProcessor<>(DEFAULT_WRITE_CONCURRENCY, this::appendInternal, this.executorService);
    }

    //region DurableDataLog Implementation

    @Override
    public void close() {
        if (!this.closed) {
            this.throttler.close();
            try {
                this.entries.releaseLock(this.clientId);
            } catch (DataLogWriterNotPrimaryException ex) {
                // Nothing. Just let it go.
            }

            this.closed = true;
        }
    }

    @Override
    public void initialize(Duration timeout) {
        long newEpoch;
        try {
            newEpoch = this.entries.acquireLock(this.clientId);
        } catch (DataLogWriterNotPrimaryException ex) {
            throw new CompletionException(ex);
        }

        synchronized (this.entries) {
            this.epoch = newEpoch;
            Entry last = this.entries.getLast();
            if (last == null) {
                this.offset = 0;
            } else {
                this.offset = last.sequenceNumber + last.data.length;
            }
        }

        this.initialized = true;
    }

    @Override
    public int getMaxAppendLength() {
        ensurePreconditions();
        return this.entries.getMaxAppendSize();
    }

    @Override
    public long getEpoch() {
        ensurePreconditions();
        return this.epoch;
    }

    @Override
    public QueueStats getQueueStatistics() {
        // InMemory DurableDataLog has almost infinite bandwidth, so no need to complicate ourselves with this.
        return QueueStats.DEFAULT;
    }

    @Override
    public CompletableFuture<LogAddress> append(ArrayView data, Duration timeout) {
        ensurePreconditions();
        return this.throttler.process(data);
    }

    @Override
    public CompletableFuture<Void> truncate(LogAddress upToAddress, Duration timeout) {
        ensurePreconditions();
        return CompletableFuture.runAsync(() -> {
            try {
                synchronized (this.entries) {
                    this.entries.truncate(upToAddress.getSequence(), this.clientId);
                }
            } catch (DataLogWriterNotPrimaryException ex) {
                throw new CompletionException(ex);
            }
        }, this.executorService);
    }

    @Override
    public CloseableIterator<ReadItem, DurableDataLogException> getReader() throws DurableDataLogException {
        ensurePreconditions();
        return new ReadResultIterator(this.entries.iterator());
    }

    //endregion

    private CompletableFuture<LogAddress> appendInternal(ArrayView data) {
        CompletableFuture<LogAddress> result;
        try {
            Entry entry = new Entry(data);
            synchronized (this.entries) {
                entry.sequenceNumber = this.offset;
                this.entries.add(entry, clientId);

                // Only update internals after a successful add.
                this.offset += entry.data.length;
            }
            result = CompletableFuture.completedFuture(new InMemoryLogAddress(entry.sequenceNumber));
        } catch (Throwable ex) {
            return FutureHelpers.failedFuture(ex);
        }

        Duration delay = this.appendDelayProvider.get();
        if (delay.compareTo(Duration.ZERO) <= 0) {
            // No delay, execute right away.
            return result;
        } else {
            // Schedule the append after the given delay.
            return result.thenComposeAsync(
                    logAddress -> FutureHelpers.delayedFuture(delay, this.executorService)
                                               .thenApply(ignored -> logAddress),
                    this.executorService);
        }
    }

    private void ensurePreconditions() {
        Exceptions.checkNotClosed(this.closed, this);
        Preconditions.checkState(this.initialized, "InMemoryDurableDataLog is not initialized.");
    }

    //region ReadResultIterator

    @RequiredArgsConstructor
    private static class ReadResultIterator implements CloseableIterator<ReadItem, DurableDataLogException> {
        private final Iterator<Entry> entryIterator;

        @Override
        public ReadItem getNext() throws DurableDataLogException {
            if (this.entryIterator.hasNext()) {
                return new ReadResultItem(this.entryIterator.next());
            }

            return null;
        }

        @Override
        public void close() {

        }
    }

    //endregion

    //region ReadResultItem

    private static class ReadResultItem implements DurableDataLog.ReadItem {
        private final byte[] payload;
        @Getter
        private final LogAddress address;

        ReadResultItem(Entry entry) {
            this.payload = entry.data;
            this.address = new InMemoryLogAddress(entry.sequenceNumber);
        }

        @Override
        public InputStream getPayload() {
            return new ByteArrayInputStream(this.payload);
        }

        @Override
        public int getLength() {
            return this.payload.length;
        }

        @Override
        public String toString() {
            return String.format("Address = %s, Length = %d", this.address, this.payload.length);
        }
    }

    //endregion

    //region EntryCollection

    static class EntryCollection {
        private final SequencedItemList<Entry> entries;
        private final AtomicReference<String> writeLock;
        private final AtomicLong epoch;
        private final int maxAppendSize;

        EntryCollection() {
            this(1024 * 1024 - 8 * 1024);
        }

        EntryCollection(int maxAppendSize) {
            this.entries = new SequencedItemList<>();
            this.writeLock = new AtomicReference<>();
            this.epoch = new AtomicLong();
            this.maxAppendSize = maxAppendSize;
        }

        public void add(Entry entry, String clientId) throws DataLogWriterNotPrimaryException {
            ensureLock(clientId);
            this.entries.add(entry);
        }

        int getMaxAppendSize() {
            return this.maxAppendSize;
        }

        Entry getLast() {
            return this.entries.getLast();
        }

        void truncate(long upToSequence, String clientId) throws DataLogWriterNotPrimaryException {
            ensureLock(clientId);
            this.entries.truncate(upToSequence);
        }

        Iterator<Entry> iterator() {
            return this.entries.read(Long.MIN_VALUE, Integer.MAX_VALUE);
        }

        long acquireLock(String clientId) throws DataLogWriterNotPrimaryException {
            Exceptions.checkNotNullOrEmpty(clientId, "clientId");
            this.writeLock.set(clientId);
            return this.epoch.incrementAndGet();
        }

        void releaseLock(String clientId) throws DataLogWriterNotPrimaryException {
            Exceptions.checkNotNullOrEmpty(clientId, "clientId");
            if (!writeLock.compareAndSet(clientId, null)) {
                throw new DataLogWriterNotPrimaryException(
                        "Unable to release exclusive write lock because the current client does not own it. Current owner: "
                                + clientId);
            }
        }

        private void ensureLock(String clientId) throws DataLogWriterNotPrimaryException {
            Exceptions.checkNotNullOrEmpty(clientId, "clientId");
            String existingLockOwner = this.writeLock.get();
            if (existingLockOwner != null && !existingLockOwner.equals(clientId)) {
                throw new DataLogWriterNotPrimaryException("Unable to perform operation because the write lock is owned by a different client " + clientId);
            }
        }
    }

    //endregion

    //region Entry

    static class Entry implements SequencedItemList.Element {
        @Getter
        long sequenceNumber = -1;
        final byte[] data;

        Entry(ArrayView inputData) {
            this.data = new byte[inputData.getLength()];
            System.arraycopy(inputData.array(), inputData.arrayOffset(), this.data, 0, this.data.length);
        }

        @Override
        public String toString() {
            return String.format("SequenceNumber = %d, Length = %d", sequenceNumber, data.length);
        }
    }

    //endregion

    //region InMemoryLogAddress

    static class InMemoryLogAddress extends LogAddress {
        InMemoryLogAddress(long sequence) {
            super(sequence);
        }

        @Override
        public int hashCode() {
            return Long.hashCode(getSequence());
        }

        @Override
        public boolean equals(Object other) {
            if (other instanceof InMemoryLogAddress) {
                return this.getSequence() == ((InMemoryLogAddress) other).getSequence();
            }

            return false;
        }
    }

    //endregion
}
