/*
 * Copyright 2011 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package grails.util;

import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.springframework.beans.BeanUtils;

/**
 * Wrapper for a value inside a cache that adds timestamp information
 * for expiration and prevents "cache storms" with a Lock.
 *
 * JMM happens-before is ensured with AtomicReference.
 *
 * Objects in cache are assumed to not change after publication.
 *
 * @author Lari Hotari
 * @since 2.3.4
 */
public class CacheEntry<V> {
    protected AtomicReference<V> valueRef=new AtomicReference<V>(null);
    protected long createdMillis;
    protected Lock writeLock=new ReentrantLock();

    public CacheEntry() {
        expire();
    }
    
    public CacheEntry(V value) {
        this.valueRef.set(value);
        resetTimestamp();
    }
    
    /**
     * Gets a value from cache. If the key doesn't exist, it will create the value using the updater callback
     * Prevents cache storms with a lock 
     * 
     * The key is always added to the cache. Null return values will also be cached.
     * You can use this together with ConcurrentLinkedHashMap to create a bounded LRU cache
     *
     * @param map the cache map
     * @param key the key to look up
     * @param timeoutMillis cache entry timeout
     * @param updater callback to create/update value
     * @param cacheEntryClass CacheEntry implementation class to use
     * @return
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public static <K, V> V getValue(ConcurrentMap<K, CacheEntry<V>> map, K key, long timeoutMillis, Callable<V> updater, Class<? extends CacheEntry> cacheEntryClass) {
        CacheEntry<V> cacheEntry = map.get(key);
        if(cacheEntry==null) {
            cacheEntry = BeanUtils.instantiate(cacheEntryClass);
            CacheEntry<V> previousEntry = map.putIfAbsent(key, cacheEntry);
            if(previousEntry != null) {
                cacheEntry = previousEntry;
            }
        }
        return cacheEntry.getValue(timeoutMillis, updater);
    }
    
    public static <K, V> V getValue(ConcurrentMap<K, CacheEntry<V>> map, K key, long timeoutMillis, Callable<V> updater) {
        return getValue(map, key, timeoutMillis, updater, CacheEntry.class);
    }
    
    /**
     * gets the current value from the entry and updates it if it's older than timeout
     *
     * updater is a callback for creating an updated value.
     *
     * @param timeout
     * @param updater
     * @return The atomic reference
     */
    public V getValue(long timeout, Callable<V> updater) {
        if (timeout < 0 || updater==null) return valueRef.get();

        if (hasExpired(timeout)) {
            try {
                long beforeLockingCreatedMillis = createdMillis;
                writeLock.lock();
                if (shouldUpdate(beforeLockingCreatedMillis)) {
                    try {
                        valueRef.set(updater.call());
                    }
                    catch (Exception e) {
                        throw new UpdateException(e);
                    }
                    resetTimestamp();
                }
            } finally {
                writeLock.unlock();
            }
        }

        return valueRef.get();
    }

    public V getValue() {
        return valueRef.get();
    }

    protected boolean hasExpired(long timeout) {
        return System.currentTimeMillis() - timeout > createdMillis;
    }

    protected boolean shouldUpdate(long beforeLockingCreatedMillis) {
        return beforeLockingCreatedMillis == createdMillis || createdMillis == 0L;
    }

    protected void resetTimestamp() {
        createdMillis = System.currentTimeMillis();
    }

    public long getCreatedMillis() {
        return createdMillis;
    }

    public void expire() {
        createdMillis = 0L;
    }
    
    public static final class UpdateException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public UpdateException(String message, Throwable cause) {
            super(message, cause);
        }

        public UpdateException(Throwable cause) {
            super(cause);
        }

        public void rethrow() throws Exception {
            if (getCause() instanceof Exception) {
                throw (Exception)getCause();
            }

            throw this;
        }
    }
}
