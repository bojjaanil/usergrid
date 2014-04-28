/*
 * Copyright 2014 The Apache Software Foundation.
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

package org.apache.usergrid.corepersistence;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.yammer.metrics.annotation.Metered;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.usergrid.persistence.ConnectedEntityRef;
import org.apache.usergrid.persistence.ConnectionRef;
import org.apache.usergrid.persistence.DynamicEntity;
import org.apache.usergrid.persistence.Entity;
import org.apache.usergrid.persistence.EntityManager;
import org.apache.usergrid.persistence.EntityRef;
import org.apache.usergrid.persistence.Identifier;
import org.apache.usergrid.persistence.IndexBucketLocator;
import org.apache.usergrid.persistence.Query;
import org.apache.usergrid.persistence.RelationManager;
import org.apache.usergrid.persistence.Results;
import org.apache.usergrid.persistence.Schema;
import static org.apache.usergrid.persistence.Schema.TYPE_APPLICATION;
import static org.apache.usergrid.persistence.Schema.TYPE_ENTITY;
import static org.apache.usergrid.persistence.Schema.defaultCollectionName;
import static org.apache.usergrid.persistence.Schema.getDefaultSchema;
import org.apache.usergrid.persistence.SimpleEntityRef;
import org.apache.usergrid.persistence.cassandra.ConnectionRefImpl;
import org.apache.usergrid.persistence.collection.CollectionScope;
import org.apache.usergrid.persistence.collection.EntityCollectionManager;
import org.apache.usergrid.persistence.collection.EntityCollectionManagerFactory;
import org.apache.usergrid.persistence.collection.impl.CollectionScopeImpl;
import org.apache.usergrid.persistence.core.scope.OrganizationScope;
import org.apache.usergrid.persistence.entities.User;
import org.apache.usergrid.persistence.graph.Edge;
import org.apache.usergrid.persistence.graph.GraphManager;
import org.apache.usergrid.persistence.graph.GraphManagerFactory;
import org.apache.usergrid.persistence.graph.impl.SimpleMarkedEdge;
import org.apache.usergrid.persistence.graph.impl.SimpleSearchByEdgeType;
import org.apache.usergrid.persistence.graph.impl.SimpleSearchEdgeType;
import org.apache.usergrid.persistence.index.EntityIndex;
import org.apache.usergrid.persistence.index.EntityIndexFactory;
import org.apache.usergrid.persistence.index.utils.EntityMapUtils;
import org.apache.usergrid.persistence.model.entity.Id;
import org.apache.usergrid.persistence.model.entity.SimpleId;
import org.apache.usergrid.persistence.model.util.UUIDGenerator;
import org.apache.usergrid.persistence.schema.CollectionInfo;
import static org.apache.usergrid.utils.InflectionUtils.singularize;
import org.apache.usergrid.utils.MapUtils;
import static org.apache.usergrid.utils.MapUtils.addMapSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.Assert;
import rx.Observable;


/**
 * Implement good-old Usergrid RelationManager with the new-fangled Core Persistence API.
 */
public class CpRelationManager implements RelationManager {

    private static final Logger logger = LoggerFactory.getLogger( CpRelationManager.class );

    private CpEntityManagerFactory emf;
    private EntityManager em;
    private UUID applicationId;

    private EntityRef headEntity;

    private org.apache.usergrid.persistence.model.entity.Entity cpHeadEntity;
    private OrganizationScope organizationScope;
    private CollectionScope applicationScope;
    private CollectionScope headEntityScope;

    private final EntityCollectionManagerFactory ecmf;
    private final EntityIndexFactory eif;
    private final GraphManagerFactory gmf;

    public static String COLLECTION_SUFFIX = "zzzcollectionzzz"; 


//    // collection scope in which to store Collection record entities
//    public static final String SYSTEM_COLLECTIONS_UUID = "b6768a08-b5d5-11e3-a495-10ddb1de66c6";
//    public static final String SYSTEM_COLLECTIONS_TYPE = "zzz_collections_zzz"; 
//
//    public static final CollectionScope SYSTEM_COLLECTIONS_SCOPE = 
//        new CollectionScopeImpl( SYSTEM_ORG_SCOPE.getOrganization(), 
//            new SimpleId( UUID.fromString(SYSTEM_COLLECTIONS_UUID), SYSTEM_COLLECTIONS_TYPE ), 
//                SYSTEM_COLLECTIONS_TYPE);


    public CpRelationManager() {
        Injector injector = Guice.createInjector( new GuiceModule() );
        ecmf = injector.getInstance( EntityCollectionManagerFactory.class );
        eif = injector.getInstance( EntityIndexFactory.class );
        gmf = injector.getInstance( GraphManagerFactory.class );
    }


    public CpRelationManager init( 
        EntityManager em, CpEntityManagerFactory emf, UUID applicationId,
        EntityRef headEntity, IndexBucketLocator indexBucketLocator ) {

        Assert.notNull( em, "Entity manager cannot be null" );
        Assert.notNull( applicationId, "Application Id cannot be null" );
        Assert.notNull( headEntity, "Head entity cannot be null" );
        Assert.notNull( headEntity.getUuid(), "Head entity uuid cannot be null" );

        this.em = em;
        this.emf = emf;
        this.applicationId = applicationId;
        this.headEntity = headEntity;

        organizationScope = emf.getOrganizationScope(applicationId);
        applicationScope = emf.getApplicationScope(applicationId);
        headEntityScope = new CollectionScopeImpl( 
            applicationScope.getOrganization(), 
            applicationScope.getOwner(), 
            Schema.defaultCollectionName( headEntity.getType()) );

        EntityCollectionManager ecm = ecmf.createCollectionManager(headEntityScope);
        cpHeadEntity = ecm.load( new SimpleId( 
            headEntity.getUuid(), headEntity.getType() )).toBlockingObservable().last();

        return this;
    }

    
    @Override
    public Set<String> getCollectionIndexes(String collectionName) throws Exception {
        final Set<String> indexes = new HashSet<String>();

        GraphManager gm = gmf.createEdgeManager(organizationScope);

        Observable<String> types= gm.getEdgeTypesFromSource( 
            new SimpleSearchEdgeType( cpHeadEntity.getId(), null ));

        Iterator<String> iter = types.toBlockingObservable().getIterator();
        while ( iter.hasNext() ) {
            indexes.add( iter.next() );
        }
        return indexes;
    }


    @Override
    public Map<String, Map<UUID, Set<String>>> getOwners() throws Exception {

        Map<EntityRef, Set<String>> containerEntities = getContainingCollections();

        Map<String, Map<UUID, Set<String>>> owners = new LinkedHashMap<String, Map<UUID, Set<String>>>();

        for ( EntityRef owner : containerEntities.keySet() ) {
            Set<String> collections = containerEntities.get( owner );
            for ( String collection : collections ) {
                MapUtils.addMapMapSet( owners, owner.getType(), owner.getUuid(), collection );
            }
        }

        return owners;
    }


    private Map<EntityRef, Set<String>> getContainingCollections() {

        Map<EntityRef, Set<String>> results = new LinkedHashMap<EntityRef, Set<String>>();

        GraphManager gm = gmf.createEdgeManager(organizationScope);
        Observable<Edge> edges = gm.loadEdgesToTarget( new SimpleSearchByEdgeType( 
            cpHeadEntity.getId(), "contains", cpHeadEntity.getVersion(), null ));

        Iterator<Edge> iter = edges.toBlockingObservable().getIterator();
        while ( iter.hasNext() ) {
            Edge edge = iter.next();

            if ( !edge.getType().endsWith(COLLECTION_SUFFIX) ) {
                continue;
            }

            String collName = edge.getType().substring(0, edge.getType().indexOf(COLLECTION_SUFFIX));

            CollectionScope collScope = new CollectionScopeImpl( 
                applicationScope.getOrganization(), 
                applicationScope.getOwner(), 
                collName );
            EntityCollectionManager ecm = ecmf.createCollectionManager(collScope);

            org.apache.usergrid.persistence.model.entity.Entity container = 
                ecm.load( edge.getSourceNode() ).toBlockingObservable().last();

            EntityRef eref = new SimpleEntityRef( 
                container.getId().getType(), container.getId().getUuid() );

            addMapSet( results, eref, edge.getType() );
        }

        EntityRef applicationRef = new SimpleEntityRef( TYPE_APPLICATION, applicationId );
        if ( !results.containsKey( applicationRef ) ) {
            addMapSet( results, applicationRef, defaultCollectionName( headEntity.getType() ) );
        }
        return results;
    }


    @SuppressWarnings("unchecked")
    @Metered(group = "core", name = "RelationManager_isOwner")
    @Override
    public boolean isCollectionMember(String collName, EntityRef entity) throws Exception {

        // TODO: review & determine if this implementation is correct

        Id entityId = new SimpleId( entity.getUuid(), entity.getType() );

        GraphManager gm = gmf.createEdgeManager(organizationScope);
        Observable<Edge> edges = gm.loadEdgesToTarget( new SimpleSearchByEdgeType( 
            entityId, collName, cpHeadEntity.getVersion(), null ));

        // TODO: more efficient way to do this?
        Iterator<Edge> iter = edges.toBlockingObservable().getIterator();
        while ( iter.hasNext() ) {
            Edge edge = iter.next();
            if ( edge.getSourceNode().equals( cpHeadEntity.getId() )) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isConnectionMember(String connectionName, EntityRef entity) throws Exception {

        // TODO: review determine if this implementation is correct

        return isCollectionMember(connectionName, entity);
    }

    @Override
    public Set<String> getCollections() throws Exception {

        Map<String, CollectionInfo> collections = getDefaultSchema().getCollections( headEntity.getType() );
        if ( collections == null ) {
            return null;
        }

        return collections.keySet();
    }

    @Override
    public Results getCollection(String collectionName, UUID startResult, int count, 
            Results.Level resultsLevel, boolean reversed) throws Exception {
        throw new UnsupportedOperationException("Not supported yet."); 
    }

    @Override
    public Results getCollection(
            String collName, Query query, Results.Level level) throws Exception {
        throw new UnsupportedOperationException("Not supported yet."); 
    }


    // add to a named collection of the head entity
    @Override
    public Entity addToCollection(String collName, EntityRef memberRef) throws Exception {

        // load the new member entity to be added to the collection
        CollectionScope memberScope = new CollectionScopeImpl( 
            applicationScope.getOrganization(), 
            applicationScope.getOwner(), 
            Schema.defaultCollectionName( memberRef.getType()));
        EntityCollectionManager memberMgr = ecmf.createCollectionManager(memberScope);

        org.apache.usergrid.persistence.model.entity.Entity memberEntity = memberMgr.load(
            new SimpleId( memberRef.getUuid(), memberRef.getType() )).toBlockingObservable().last();

        // create graph edge connection from head entity to member entity
        Edge edge = new SimpleMarkedEdge( cpHeadEntity.getId(), collName + COLLECTION_SUFFIX, 
            memberEntity.getId(), UUIDGenerator.newTimeUUID(), false );

        GraphManager gm = gmf.createEdgeManager(organizationScope);
        gm.writeEdge(edge).toBlockingObservable().last();

        // index connection from head entity to member entity
        EntityIndex ei = eif.createEntityIndex(organizationScope, applicationScope);
        
        ei.indexConnection( cpHeadEntity, collName + COLLECTION_SUFFIX, memberEntity, memberScope );

        logger.debug("Added entity {}:{} to collection {}", 
            new String[] { memberRef.getUuid().toString(), memberRef.getType(), collName }); 

        logger.debug("With head entity scope is {}:{}:{}",
            new String[] { 
                headEntityScope.getOrganization().toString(), 
                headEntityScope.getOwner().toString(),
                applicationScope.getName()}); 

        return em.get( memberRef );
    }


    @Override
    public Entity addToCollections(List<EntityRef> owners, String collName) throws Exception {

        // TODO: this addToCollections() implementation seems wrong.
        for ( EntityRef eref : owners ) {
            addToCollection( collName, eref ); 
        }

        return null;
    }


    @Override
    @Metered(group = "core", name = "RelationManager_createItemInCollection")
    public Entity createItemInCollection(
        String collName, String itemType, Map<String, Object> properties) throws Exception {

        if ( headEntity.getUuid().equals( applicationId ) ) {
            if ( itemType.equals( TYPE_ENTITY ) ) {
                itemType = singularize( collName );
            }

// TODO: complete when Role support is ready 
//
//            if ( itemType.equals( TYPE_ROLE ) ) {
//                Long inactivity = ( Long ) properties.get( PROPERTY_INACTIVITY );
//                if ( inactivity == null ) {
//                    inactivity = 0L;
//                }
//                return em.createRole( ( String ) properties.get( PROPERTY_NAME ),
//                        ( String ) properties.get( PROPERTY_TITLE ), inactivity );
//            }
            return em.create( itemType, properties );
        }

// TODO: complete when Role support is ready 
//
//        else if ( headEntity.getType().equals( Group.ENTITY_TYPE ) && ( collectionName.equals( COLLECTION_ROLES ) ) ) {
//            UUID groupId = headEntity.getUuid();
//            String roleName = ( String ) properties.get( PROPERTY_NAME );
//            return em.createGroupRole( groupId, roleName, ( Long ) properties.get( PROPERTY_INACTIVITY ) );
//        }

        CollectionInfo collection = getDefaultSchema().getCollection( headEntity.getType(), collName );
        if ( ( collection != null ) && !collection.getType().equals( itemType ) ) {
            return null;
        }

        properties = getDefaultSchema().cleanUpdatedProperties( itemType, properties, true );

        Entity itemEntity = em.create( itemType, properties );

        if ( itemEntity != null ) {

            addToCollection( collName, itemEntity );

            if ( collection.getLinkedCollection() != null ) {
                getRelationManager( itemEntity )
                    .addToCollection( collection.getLinkedCollection(), getHeadEntity());
            }
        }

        return itemEntity;  
    }

    @Override
    public void removeFromCollection(String collName, EntityRef memberRef) throws Exception {

//        if ( !collectionEntityExists(collName) ) {
//            return;
//        }
//
//        // look up collection entity in system collections scope 
//        org.apache.usergrid.persistence.model.entity.Entity collectionEntity = 
//            getCollectionEntity(collName);

        // load the entity to be added to the collection
        CollectionScope memberScope = new CollectionScopeImpl( 
            applicationScope.getOrganization(), 
            applicationScope.getOwner(), 
            Schema.defaultCollectionName( memberRef.getType() ));
        EntityCollectionManager memberMgr = ecmf.createCollectionManager(memberScope);

        org.apache.usergrid.persistence.model.entity.Entity memberEntity = memberMgr.load(
            new SimpleId( memberRef.getUuid(), memberRef.getType() )).toBlockingObservable().last();

        EntityIndex ei = eif.createEntityIndex(organizationScope, applicationScope);
        ei.deindexConnection(cpHeadEntity.getId(), collName + COLLECTION_SUFFIX, memberEntity);

        Edge edge = new SimpleMarkedEdge( cpHeadEntity.getId(), collName + COLLECTION_SUFFIX, 
            memberEntity.getId(), UUIDGenerator.newTimeUUID(), false );
        GraphManager gm = gmf.createEdgeManager(organizationScope);
        gm.deleteEdge(edge).toBlockingObservable().last();
    }


//    private boolean collectionEntityExists(String name) {
//
//        EntityIndex cci = eif.createEntityIndex( SYSTEM_ORG_SCOPE, SYSTEM_COLLECTIONS_SCOPE );
//
//        org.apache.usergrid.persistence.index.query.Query q = 
//            org.apache.usergrid.persistence.index.query.Query.fromQL( 
//                "applicationId = '" + applicationId.toString() + "' and name = '" + name + "'");
//
//        org.apache.usergrid.persistence.index.query.Results results = 
//            cci.search( SYSTEM_COLLECTIONS_SCOPE, q);
//
//        boolean exists = !results.isEmpty();
//
////        logger.debug("Confirming that collection exists {} : {}", name, exists);
//        return exists;
//    }
//
//
//    /**
//     * Get entity that represents a collection.
//     * If it does not exist, create it and add a connection to it from the application.
//     */
//    private org.apache.usergrid.persistence.model.entity.Entity createCollectionEntity(
//            String name, String type) {
//
//        EntityCollectionManager ccm = ecmf.createCollectionManager(SYSTEM_COLLECTIONS_SCOPE);
//        EntityIndex cci = eif.createEntityIndex( SYSTEM_ORG_SCOPE, SYSTEM_COLLECTIONS_SCOPE );
//
//        org.apache.usergrid.persistence.model.entity.Entity collectionEntity = 
//            new org.apache.usergrid.persistence.model.entity.Entity(
//                new SimpleId(UUIDGenerator.newTimeUUID(), "collection"));
//        collectionEntity.setField(new StringField("name", name));
//        collectionEntity.setField(new StringField("type", type));
//        collectionEntity.setField(new UUIDField("applicationId", applicationId));
//
//        ccm.write(collectionEntity).toBlockingObservable().last();
//        cci.index(SYSTEM_COLLECTIONS_SCOPE, collectionEntity);
//
//        Edge edge = new SimpleMarkedEdge( headApplicationScope.getOwner(), "contains", 
//            collectionEntity.getId(), UUIDGenerator.newTimeUUID(), false );
//        GraphManager gm = gmf.createEdgeManager(headOrganizationScope);
//        gm.writeEdge(edge).toBlockingObservable().last();
//
//        logger.debug("Created Collection Entity for name: " + name);
//
//        return collectionEntity;
//    } 
//
//
//    /**
//     * Get entity that represents a collection.
//     * If it does not exist, create it and add a connection to it from the application.
//     */
//    private org.apache.usergrid.persistence.model.entity.Entity getCollectionEntity(String name) {
//
//        EntityIndex cci = eif.createEntityIndex( SYSTEM_ORG_SCOPE, SYSTEM_COLLECTIONS_SCOPE );
//
//        org.apache.usergrid.persistence.index.query.Query q = 
//            org.apache.usergrid.persistence.index.query.Query.fromQL( 
//                "applicationId = '" + applicationId.toString() + "' and name = '" + name + "'");
//
//        org.apache.usergrid.persistence.index.query.Results results = 
//            cci.search( SYSTEM_COLLECTIONS_SCOPE, q);
//
//        return results.iterator().next();
//    } 


    @Override
    public void copyRelationships(String srcRelationName, EntityRef dstEntityRef, 
            String dstRelationName) throws Exception {
        throw new UnsupportedOperationException("Not supported yet."); 
    }

    @Override
    public Results searchCollection(String collName, Query query) throws Exception {

        if ( query == null ) {
            query = new Query();
        }

        headEntity = em.validate( headEntity );
        CollectionInfo collection = getDefaultSchema().getCollection( headEntity.getType(), collName );
        query.setEntityType( collection.getType() );

        org.apache.usergrid.persistence.index.query.Query cpQuery = createCpQuery( query );

        EntityIndex ei = eif.createEntityIndex(organizationScope, applicationScope);
      
        logger.debug("Searching collection {}", collName);
        logger.debug("Searching head entity scope {}:{}:{}",
            new String[] { 
                headEntityScope.getOrganization().toString(), 
                headEntityScope.getOwner().toString(),
                applicationScope.getName()}); 

        org.apache.usergrid.persistence.index.query.Results cpResults = 
            ei.searchConnections(cpHeadEntity, collName + COLLECTION_SUFFIX, cpQuery );

        if ( cpResults.isEmpty() ) {
            Results results = new Results();
            return results;
        }

        List<Entity> entities = new ArrayList<Entity>();

        for ( org.apache.usergrid.persistence.model.entity.Entity e : cpResults.getEntities() ) {
            Entity entity = new DynamicEntity( e.getId().getType(), e.getId().getUuid() );
            entity.setUuid( e.getId().getUuid() );
            Map<String, Object> entityMap = EntityMapUtils.toMap( e );
            entity.addProperties( entityMap ); 
            entities.add( entity );
        }

        return Results.fromEntities( entities );
    }


    private org.apache.usergrid.persistence.index.query.Query createCpQuery( Query query ) {

        org.apache.usergrid.persistence.index.query.Query cpQuery =
           new org.apache.usergrid.persistence.index.query.Query();

        cpQuery.setCollection( query.getCollection() );
        cpQuery.setConnectionType( query.getConnectionType() );
        cpQuery.setCursor( query.getCursor() );
        cpQuery.setEntityType( query.getEntityType() );
        cpQuery.setFinishTime( query.getFinishTime() );
        cpQuery.setLimit( query.getLimit() );
        cpQuery.setPad( query.isPad() );
        cpQuery.setPermissions( query.getPermissions() );
        cpQuery.setQl( query.getQl() );
        cpQuery.setReversed( query.isReversed() );
        cpQuery.setStartTime( query.getStartTime() );

        if ( cpQuery.getRootOperand() == null ) {

            // a name alias or email alias was specified
            if ( query.containsSingleNameOrEmailIdentifier() ) {

                Identifier ident = query.getSingleIdentifier();

                // an email was specified.  An edge case that only applies to users.  
                // This is fulgy to put here, but required.
                if ( query.getEntityType().equals( User.ENTITY_TYPE ) && ident.isEmail() ) {

                    org.apache.usergrid.persistence.index.query.Query newQuery = 
                        org.apache.usergrid.persistence.index.query.Query.fromQL(
                            "select * where email='" + query.getSingleNameOrEmailIdentifier()+ "'");

                    cpQuery.setRootOperand( newQuery.getRootOperand() );
                }

                // use the ident with the default alias. could be an email
                else {
                    org.apache.usergrid.persistence.index.query.Query newQuery = 
                        org.apache.usergrid.persistence.index.query.Query.fromQL(
                            "select * where email='" + query.getSingleNameOrEmailIdentifier()+ "'");

                    cpQuery.setRootOperand( newQuery.getRootOperand() );
                }

            } else if ( query.containsSingleUuidIdentifier() ) {

                org.apache.usergrid.persistence.index.query.Query newQuery = 
                    org.apache.usergrid.persistence.index.query.Query.fromQL(
                        "select * where uuid='" + query.getSingleUuidIdentifier() + "'");

                cpQuery.setRootOperand( newQuery.getRootOperand() );
            }

        }

        return cpQuery;
    }


    @Override
    @Metered(group = "core", name = "RelationManager_createConnection_connection_ref")
    public ConnectionRef createConnection( ConnectionRef connection ) throws Exception {
        throw new UnsupportedOperationException("Not supported yet."); 
    }


    @Override
    @Metered(group = "core", name = "RelationManager_createConnection_connectionType")
    public ConnectionRef createConnection( 
            String connectionType, EntityRef connectedEntityRef ) throws Exception {

        headEntity = em.validate( headEntity );
        connectedEntityRef = em.validate( connectedEntityRef );

        ConnectionRefImpl connection = new ConnectionRefImpl( 
            headEntity, connectionType, connectedEntityRef );

        CollectionScope targetScope = new CollectionScopeImpl( 
            applicationScope.getOrganization(), 
            applicationScope.getOwner(), 
            Schema.defaultCollectionName( connectedEntityRef.getType() ));

        EntityCollectionManager targetEcm = ecmf.createCollectionManager(targetScope);
        org.apache.usergrid.persistence.model.entity.Entity targetEntity = targetEcm.load(
            new SimpleId( connectedEntityRef.getUuid(), connectedEntityRef.getType() ))
                .toBlockingObservable().last();

        EntityIndex ei = eif.createEntityIndex(organizationScope, applicationScope);
        ei.indexConnection(cpHeadEntity, connectionType, targetEntity, targetScope);

        return connection;
    }


    @Override
    @Metered(group = "core", name = "RelationManager_createConnection_paired_connection_type")
    public ConnectionRef createConnection( 
            String pairedConnectionType, EntityRef pairedEntity, String connectionType,
            EntityRef connectedEntityRef ) throws Exception {
        
        throw new UnsupportedOperationException("Paired connections not supported"); 
    }


    @Override
    @Metered(group = "core", name = "RelationManager_createConnection_connected_entity_ref")
    public ConnectionRef createConnection( ConnectedEntityRef... connections ) throws Exception {

        throw new UnsupportedOperationException("Paired connections not supported"); 
    }

    @Override
    public ConnectionRef connectionRef(
            String connectionType, 
            EntityRef connectedEntityRef) throws Exception {

        ConnectionRef connection = new ConnectionRefImpl( 
                headEntity, connectionType, connectedEntityRef );

        return connection;
    }

    @Override
    public ConnectionRef connectionRef(String pairedConnectionType, EntityRef pairedEntity, 
            String connectionType, EntityRef connectedEntityRef) throws Exception {
        throw new UnsupportedOperationException("Paired connections not supported"); 
    }

    @Override
    public ConnectionRef connectionRef(ConnectedEntityRef... connections) {
        throw new UnsupportedOperationException("Paired connections not supported"); 
    }

    @Override
    public void deleteConnection(ConnectionRef connectionRef) throws Exception {
        throw new UnsupportedOperationException("Not supported yet."); 
    }

    @Override
    public Set<String> getConnectionTypes(UUID connectedEntityId) throws Exception {
        throw new UnsupportedOperationException("Not supported yet."); 
    }

    @Override
    public Set<String> getConnectionTypes() throws Exception {
        throw new UnsupportedOperationException("Not supported yet."); 
    }

    @Override
    public Set<String> getConnectionTypes(boolean filterConnection) throws Exception {
        throw new UnsupportedOperationException("Not supported yet."); 
    }

    @Override
    public Results getConnectedEntities(String connectionType, String connectedEntityType, 
            Results.Level resultsLevel) throws Exception {
        throw new UnsupportedOperationException("Not supported yet."); 
    }

    @Override
    public Results getConnectingEntities(String connectionType, String connectedEntityType, 
            Results.Level resultsLevel) throws Exception {
        throw new UnsupportedOperationException("Not supported yet."); 
    }

    @Override
    public Results getConnectingEntities(String connectionType, String entityType, 
            Results.Level level, int count) throws Exception {
        throw new UnsupportedOperationException("Not supported yet."); 
    }

    @Override
    public Results searchConnectedEntities(Query query) throws Exception {
        throw new UnsupportedOperationException("Not supported yet."); 
    }

    @Override
    public Set<String> getConnectionIndexes(String connectionType) throws Exception {
        throw new UnsupportedOperationException("Not supported yet."); 
    }

    
    private CpRelationManager getRelationManager( EntityRef headEntity ) {
        CpRelationManager rmi = new CpRelationManager();
        rmi.init( em, null, applicationId, headEntity, null);
        return rmi;
    }


    /** side effect: converts headEntity into an Entity if it is an EntityRef! */
    private Entity getHeadEntity() throws Exception {
        Entity entity = null;
        if ( headEntity instanceof Entity ) {
            entity = ( Entity ) headEntity;
        }
        else {
            entity = em.get( headEntity );
            headEntity = entity;
        }
        return entity;
    }

}