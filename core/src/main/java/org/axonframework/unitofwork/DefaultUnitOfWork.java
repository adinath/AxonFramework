/*
 * Copyright (c) 2010. Axon Framework
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.axonframework.unitofwork;

import org.axonframework.domain.AggregateRoot;
import org.axonframework.domain.Event;
import org.axonframework.eventhandling.EventBus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

/**
 * Implementation of the UnitOfWork that buffers all published events until it is committed. Aggregates that have not
 * been explicitly save in their aggregates will be saved when the UnitOfWork committs.
 * <p/>
 * This implementation requires a mechanism that explicitly commits or rolls back.
 *
 * @author Allard Buijze
 * @since 0.6
 */
public class DefaultUnitOfWork implements UnitOfWork {

    private final Map<AggregateRoot, AggregateEntry> registeredAggregates = new LinkedHashMap<AggregateRoot, AggregateEntry>();
    private final Queue<EventEntry> eventsToPublish = new LinkedList<EventEntry>();
    private final Map<AggregateRoot, List<UnitOfWorkListener>> listeners = new HashMap<AggregateRoot, List<UnitOfWorkListener>>();
    private boolean publishEventImmediately;

    @Override
    public void rollback() {
        registeredAggregates.clear();
        eventsToPublish.clear();
        for (UnitOfWorkListener listener : allListeners()) {
            listener.onRollback();
        }
        listeners.clear();
    }

    @Override
    public void commitAggregate(AggregateRoot aggregate) {
        if (!isRegistered(aggregate)) {
            throw new IllegalStateException("Cannot commit an aggregate that has not been registered.");
        }
        try {
            performPartialCommit(aggregate);
        }
        catch (RuntimeException ex) {
            for (UnitOfWorkListener listener : listenersFor(aggregate)) {
                listener.onRollback();
            }
            throw ex;
        }
    }

    @Override
    public boolean isRegistered(AggregateRoot aggregate) {
        return registeredAggregates.containsKey(aggregate);
    }

    @Override
    public void commit() {
        try {
            performCommit();
        } catch (RuntimeException e) {
            rollback();
            throw e;
        }
    }

    @Override
    public <T extends AggregateRoot> void registerAggregate(T aggregate, Long expectedVersion,
                                                            SaveAggregateCallback<T> callback) {
        registeredAggregates.put(aggregate, new AggregateEntry<T>(aggregate, callback));
    }

    @Override
    public void registerListener(AggregateRoot aggregate, UnitOfWorkListener listener) {
        listenersFor(aggregate).add(listener);
    }

    @Override
    public void publishEvent(Event event, EventBus eventBus) {
        if (publishEventImmediately) {
            eventBus.publish(event);
        } else {
            eventsToPublish.add(new EventEntry(event, eventBus));
        }
    }

    private void performPartialCommit(AggregateRoot aggregateRoot) {
        this.publishEventImmediately = true;
        for (UnitOfWorkListener listener : listenersFor(aggregateRoot)) {
            listener.onPrepareCommit();
        }
        registeredAggregates.remove(aggregateRoot).saveAggregate();
        for (UnitOfWorkListener listener : listenersFor(aggregateRoot)) {
            listener.afterCommit();
        }
        this.publishEventImmediately = false;
    }

    private void performCommit() {
        for (UnitOfWorkListener listener : allListeners()) {
            listener.onPrepareCommit();
        }
        for (AggregateEntry entry : registeredAggregates.values()) {
            entry.saveAggregate();
        }
        registeredAggregates.clear();
        while (!eventsToPublish.isEmpty()) {
            eventsToPublish.poll().publishEvent();
        }
        for (UnitOfWorkListener listener : allListeners()) {
            listener.afterCommit();
        }
    }

    private List<UnitOfWorkListener> listenersFor(AggregateRoot aggregate) {
        if (!listeners.containsKey(aggregate)) {
            listeners.put(aggregate, new ArrayList<UnitOfWorkListener>());
        }
        return listeners.get(aggregate);
    }

    private List<UnitOfWorkListener> allListeners() {
        List<UnitOfWorkListener> allListeners = new ArrayList<UnitOfWorkListener>();
        for (List<UnitOfWorkListener> listenerList : listeners.values()) {
            allListeners.addAll(listenerList);
        }
        return allListeners;
    }

    private static class EventEntry {

        private final Event event;
        private final EventBus eventBus;

        public EventEntry(Event event, EventBus eventBus) {
            this.event = event;
            this.eventBus = eventBus;
        }

        public void publishEvent() {
            eventBus.publish(event);
        }
    }

    private static class AggregateEntry<T extends AggregateRoot> {

        private final T aggregateRoot;
        private final SaveAggregateCallback<T> callback;

        public AggregateEntry(T aggregateRoot, SaveAggregateCallback<T> callback) {
            this.aggregateRoot = aggregateRoot;
            this.callback = callback;
        }

        public void saveAggregate() {
            callback.save(aggregateRoot);
        }
    }
}