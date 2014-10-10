/*
 *
 *  * Licensed to the Apache Software Foundation (ASF) under one or more
 *  *  contributor license agreements.  The ASF licenses this file to You
 *  * under the Apache License, Version 2.0 (the "License"); you may not
 *  * use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  *     http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.  For additional information regarding
 *  * copyright in this work, please see the NOTICE file in the top level
 *  * directory of this distribution.
 *
 */

package org.apache.usergrid.persistence.collection.impl;

import com.google.inject.assistedinject.Assisted;
import com.netflix.astyanax.MutationBatch;
import org.apache.usergrid.persistence.collection.CollectionScope;
import org.apache.usergrid.persistence.collection.EntityVersionCleanupFactory;
import org.apache.usergrid.persistence.collection.event.EntityDeleted;
import org.apache.usergrid.persistence.collection.mvcc.MvccEntitySerializationStrategy;
import org.apache.usergrid.persistence.collection.mvcc.MvccLogEntrySerializationStrategy;
import org.apache.usergrid.persistence.core.task.Task;
import org.apache.usergrid.persistence.model.entity.Id;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rx.Observable;
import rx.functions.Action1;
import rx.functions.Func1;
import rx.schedulers.Schedulers;

import java.util.List;
import java.util.UUID;

/**
 * Fires Cleanup Task
 */
public class EntityDeletedTask implements Task<Void> {
    private EntityVersionCleanupFactory entityVersionCleanupFactory;
    private MvccLogEntrySerializationStrategy logEntrySerializationStrategy;
    private MvccEntitySerializationStrategy entitySerializationStrategy;
    private List<EntityDeleted> listeners;
    private CollectionScope collectionScope;
    private Id entityId;
    private UUID version;
    private static final Logger LOG =  LoggerFactory.getLogger(EntityDeletedTask.class);

    public EntityDeletedTask(EntityVersionCleanupFactory entityVersionCleanupFactory,
                             final MvccLogEntrySerializationStrategy logEntrySerializationStrategy,
                             final MvccEntitySerializationStrategy entitySerializationStrategy,
                             final List<EntityDeleted> listeners,
                             CollectionScope collectionScope,
                             @Assisted Id entityId, @Assisted UUID version){
        this.entityVersionCleanupFactory = entityVersionCleanupFactory;
        this.logEntrySerializationStrategy = logEntrySerializationStrategy;
        this.entitySerializationStrategy = entitySerializationStrategy;
        this.listeners = listeners;
        this.collectionScope = collectionScope;
        this.entityId = entityId;
        this.version = version;
    }

    @Override
    public void exceptionThrown(Throwable throwable) {
        LOG.error( "Unable to run update task for collection {} with entity {} and version {}",
                new Object[] { collectionScope, entityId, version }, throwable );
    }

    @Override
    public Void rejected() {
        try {
            call();
        }
        catch ( Exception e ) {
            throw new RuntimeException( "Exception thrown in call task", e );
        }

        return null;
    }

    @Override
    public Void call() throws Exception {
        entityVersionCleanupFactory.getTask(entityId,version).call();
        fireEvents();
        final MutationBatch entityDelete = entitySerializationStrategy.delete(collectionScope, entityId, version);
        final MutationBatch logDelete = logEntrySerializationStrategy.delete(collectionScope, entityId, version);
        entityDelete.execute();
        logDelete.execute();
        return null;
    }

    private void fireEvents() {
        final int listenerSize = listeners.size();

        if ( listenerSize == 0 ) {
            return;
        }

        if ( listenerSize == 1 ) {
            listeners.get( 0 ).deleted( collectionScope, entityId,version );
            return;
        }

        LOG.debug( "Started firing {} listeners", listenerSize );

        //if we have more than 1, run them on the rx scheduler for a max of 8 operations at a time
        Observable.from(listeners)
                .parallel( new Func1<Observable<EntityDeleted>, Observable<EntityDeleted>>() {

                    @Override
                    public Observable<EntityDeleted> call(
                            final Observable<EntityDeleted> entityVersionDeletedObservable ) {

                        return entityVersionDeletedObservable.doOnNext( new Action1<EntityDeleted>() {
                            @Override
                            public void call( final EntityDeleted listener ) {
                                listener.deleted(collectionScope, entityId, version);
                            }
                        } );
                    }
                }, Schedulers.io() ).toBlocking().last();

        LOG.debug( "Finished firing {} listeners", listenerSize );
    }


}