/*******************************************************************************
 * Copyright (c) <2013>, California Institute of Technology ("Caltech"). U.S.
 * Government sponsorship acknowledged.
 * 
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * - Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer. - Redistributions in binary
 * form must reproduce the above copyright notice, this list of conditions and
 * the following disclaimer in the documentation and/or other materials provided
 * with the distribution. - Neither the name of Caltech nor its operating
 * division, the Jet Propulsion Laboratory, nor the names of its contributors
 * may be used to endorse or promote products derived from this software without
 * specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 ******************************************************************************/

package gov.nasa.jpl.view_repo.webscripts;

import gov.nasa.jpl.mbee.util.Pair;
import gov.nasa.jpl.mbee.util.TimeUtils;
import gov.nasa.jpl.mbee.util.Timer;
import gov.nasa.jpl.view_repo.db.PostgresHelper;
import gov.nasa.jpl.view_repo.util.Acm;
import gov.nasa.jpl.view_repo.util.EmsScriptNode;
import gov.nasa.jpl.view_repo.util.NodeUtil;
import gov.nasa.jpl.view_repo.util.WorkspaceNode;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletResponse;

import org.alfresco.repo.model.Repository;
import org.alfresco.repo.security.authentication.AuthenticationUtil;
import org.alfresco.service.ServiceRegistry;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.site.SiteInfo;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.extensions.webscripts.Cache;
import org.springframework.extensions.webscripts.Status;
import org.springframework.extensions.webscripts.WebScriptRequest;

/**
 * 
 * @author Rahul Kumar
 * 
 */
public class Model2Postgres extends AbstractJavaWebScript {

    PostgresHelper pgh = null;

    static Logger logger = Logger.getLogger( Model2Postgres.class );

    public Model2Postgres() {
        super();
    }

    public Model2Postgres( Repository repositoryHelper, ServiceRegistry registry ) {
        super( repositoryHelper, registry );
    }

    /**
     * Webscript entry point
     */
    @Override
    protected Map< String, Object > executeImpl( WebScriptRequest req,
                                                 Status status, Cache cache ) {
        Model2Postgres instance =
                new Model2Postgres( repository, getServices() );
        return instance.executeImplImpl( req, status, cache, runWithoutTransactions );
    }

    @Override
    protected Map< String, Object >
            executeImplImpl( WebScriptRequest req, Status status, Cache cache ) {
        Timer timer = new Timer();
        String user = AuthenticationUtil.getFullyAuthenticatedUser();
        printHeader(user, logger, req);

        Map< String, Object > model = new HashMap< String, Object >();
        JSONObject json = null;

        if ( validateRequest( req, status ) || true ) {
            WorkspaceNode workspace = getWorkspace( req );
            pgh = new PostgresHelper( workspace );
            try {
                String sitesReq = req.getParameter( "siteId" );
                String timestamp = req.getParameter( "timestamp" );
                Date dateTime = TimeUtils.dateFromTimestamp( timestamp );
                JSONArray jsonArray =
                        handleSite( workspace, dateTime, sitesReq );
                json = new JSONObject();
                json.put( "sites", jsonArray );
            } catch ( JSONException e ) {
                log( Level.ERROR, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                     "JSON could not be created\n" );
                e.printStackTrace();
            } catch ( Exception e ) {
                log( Level.ERROR, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                     "Internal error stack trace:\n %s \n",
                     e.getLocalizedMessage() );
                e.printStackTrace();
            } finally {
                pgh.close();
            }
        }
        if ( json == null ) {
            model.put( "res", createResponseJson() );
        } else {
            model.put( "res", NodeUtil.jsonToString( json ) );
        }
        status.setCode( responseStatus.getCode() );

        printFooter(user, logger, timer);

        return model;
    }

    /**
     * In case something isn't in the DB, put it in with relationship to its owner
     * @param node
     * @param dateTime
     * @param workspace
     * @param name
     * @return
     */
    public int buildGraphDb( EmsScriptNode node, Date dateTime,
                              WorkspaceNode workspace, String name ) {
        int nodesInserted = 0;
        List< Pair< String, String >> edges =
                new ArrayList< Pair< String, String >>();
        List< Pair< String, String >> documentEdges =
                new ArrayList< Pair< String, String >>();

        // FIXME:since there might be a simultaneous load, this should only load in itself
        // with relationships to the node that ones it.
        if (pgh == null) pgh = new PostgresHelper(workspace);
        try {
            pgh.connect();
            nodesInserted =
                    insertNodes( node, pgh, dateTime, edges, documentEdges,
                                 workspace, name );
        } catch ( Exception e ) {

        } finally {
            pgh.close();
        }

        if ( logger.isInfoEnabled() ) {
            logger.info( "Inserting edges into DB" );
        }

        try {
            pgh.connect();
            for ( Pair< String, String > entry : edges ) {
                try {
                    pgh.insertEdge( entry.first, entry.second,
                                    PostgresHelper.DbEdgeTypes.REGULAR );
                } catch ( Exception e ) {
                    logger.warn( String.format( "could not insert edge %s:%s:%s",
                                                entry.first,
                                                entry.second,
                                                PostgresHelper.DbEdgeTypes.REGULAR ) );
                }
            }
            for ( Pair< String, String > entry : documentEdges ) {
                try {
                    pgh.insertEdge( entry.first, entry.second,
                                    PostgresHelper.DbEdgeTypes.DOCUMENT );
                } catch ( Exception e ) {
                    logger.warn( String.format( "could not insert edge %s:%s:%s",
                                                entry.first,
                                                entry.second,
                                                PostgresHelper.DbEdgeTypes.DOCUMENT ) );
                }
            }
        } catch ( Exception e ) {

        } finally {
            pgh.close();
        }

        return nodesInserted;
    }

    private JSONArray handleSite( WorkspaceNode workspace, Date dateTime,
                                  String siteId ) throws JSONException {
        JSONArray json = new JSONArray();
        EmsScriptNode siteNode;
        String name;
        SiteInfo siteIdInfo = null;
        if ( siteId != null ) {
            siteIdInfo = services.getSiteService().getSite( siteId );
        }
        List< SiteInfo > sites;
        if ( siteIdInfo != null ) {
            sites = new ArrayList< SiteInfo >();
            sites.add( siteIdInfo );
        } else {
            sites = services.getSiteService().listSites( null );
        }
        List< Pair< String, String >> edges =
                new ArrayList< Pair< String, String >>();
        List< Pair< String, String >> documentEdges =
                new ArrayList< Pair< String, String >>();

        int count = 0;
        int numSites = sites.size();
        for ( SiteInfo siteInfo : sites ) {
            count++;
            if ( logger.isInfoEnabled() ) {
                logger.info( String.format( "Processing %d of %d: %s", count,
                                            numSites, siteInfo.getShortName() ) );
            }
            JSONObject siteJson = new JSONObject();

            NodeRef siteRef = siteInfo.getNodeRef();

            if ( dateTime != null ) {
                siteRef = NodeUtil.getNodeRefAtTime( siteRef, dateTime );
            }

            if ( siteRef != null ) {
                siteNode = new EmsScriptNode( siteRef, services );
                name = siteNode.getName();

                if ( workspace == null
                     || ( workspace != null && workspace.contains( siteNode ) ) ) {
                    try {
                        pgh.connect();

                        for ( EmsScriptNode n : siteNode.getChildNodes() ) {
                            if ( n.getName().equals( "Models" ) ) {
                                for ( EmsScriptNode c : n.getChildNodes() ) {
                                    int nodesInserted =
                                            insertNodes( c, pgh, dateTime,
                                                         edges, documentEdges,
                                                         workspace, name );
                                    siteJson.put( "sysmlid", name );
                                    siteJson.put( "name", siteInfo.getTitle() );
                                    siteJson.put( "elementCount", nodesInserted );
                                    json.put( siteJson );
                                }
                            }
                        }
                    } catch ( ClassNotFoundException | SQLException e ) {
                        e.printStackTrace();
                    } finally {
                        pgh.close();
                    }
                }
            }
        }

        if ( logger.isInfoEnabled() ) {
            logger.info( "Inserting edges into DB" );
        }

        try {
            pgh.connect();
            for ( Pair< String, String > entry : edges ) {
                try {
                    pgh.insertEdge( entry.first, entry.second,
                                    PostgresHelper.DbEdgeTypes.REGULAR );
                } catch ( Exception e ) {}
            }
            for ( Pair< String, String > entry : documentEdges ) {
                try {
                    pgh.insertEdge( entry.first, entry.second,
                                    PostgresHelper.DbEdgeTypes.DOCUMENT );
                } catch ( Exception e ) {}
            }
        } catch ( ClassNotFoundException | SQLException e ) {
            e.printStackTrace();
        } finally {
            pgh.close();
        }

        if ( logger.isInfoEnabled() ) {
            logger.info( "Completed edge insertions" );
        }

        return json;
    }

    protected int insertNodes( EmsScriptNode n, PostgresHelper pgh, Date dt,
                               List< Pair< String, String >> edges,
                               List< Pair< String, String >> documentEdges,
                               WorkspaceNode ws, String parentId ) {

        int i = 0;

        if ( !n.getSysmlId().endsWith( "_pkg" ) ) {
            pgh.insertNode( n.getNodeRef().toString(),
                            NodeUtil.getVersionedRefId( n ), n.getSysmlId() );

            i++;

            // documentation edges
            String doc = (String)n.getProperty( Acm.ACM_DOCUMENTATION );
            NodeUtil.processDocumentEdges( n.getSysmlId(), doc, documentEdges );

            String view2viewProperty =
                    (String)n.getProperty( Acm.ACM_VIEW_2_VIEW );
            if ( view2viewProperty != null ) {
                NodeUtil.processV2VEdges( n.getSysmlId(),
                                          new JSONArray( view2viewProperty ),
                                          documentEdges );
            }

            NodeRef contents =
                    (NodeRef)n.getNodeRefProperty( Acm.ACM_CONTENTS, true,
                                                   null, null );
            if ( contents != null ) {
                NodeUtil.processContentsNodeRef( n.getSysmlId(), contents,
                                                 documentEdges );
            }

            NodeRef iss =
                    (NodeRef)n.getNodeRefProperty( Acm.ACM_INSTANCE_SPECIFICATION_SPECIFICATION,
                                                   null, null );
            if ( iss != null ) {
                NodeUtil.processInstanceSpecificationSpecificationNodeRef( n.getSysmlId(),
                                                                           iss,
                                                                           documentEdges );
            }
        }

        try {
            // traverse alfresco containment since ownedChildren may not be
            // accurate
            // need to get reified package node
            EmsScriptNode nPkg = n.getParent().childByNamePath( n.getName() + "_pkg" );
            if (nPkg != null) {
                for ( EmsScriptNode cn : nPkg.getChildNodes() ) {
                    String nSysmlId = n.getSysmlId().replace( "_pkg", "" );
                    String cnSysmlId = cn.getSysmlId().replace( "_pkg", "" );
    
                    if ( !cn.isDeleted() ) {
                        // add containment edges (no _pkg) otherwise results in
                        // duplicate edge
                        if ( !cn.getSysmlId().endsWith( "_pkg" ) ) {
                            edges.add( new Pair< String, String >( nSysmlId,
                                                                   cnSysmlId ) );
                        }
                        i +=
                                insertNodes( cn, pgh, dt, edges, documentEdges, ws,
                                             cnSysmlId );
                    }
                }
            }
        } catch ( org.alfresco.service.cmr.repository.MalformedNodeRefException mnre ) {
            // keep going and ignore
            logger.error( String.format( "could not get children for parent %s:",
                                         n.getId() ) );
            mnre.printStackTrace();
        }

        return i;
    }

    /**
     * Validate the request and check some permissions
     */
    @Override
    protected boolean validateRequest( WebScriptRequest req, Status status ) {
        if ( !checkRequestContent( req ) ) {
            return false;
        }

        String id = req.getServiceMatch().getTemplateVars().get( WORKSPACE_ID );
        if ( !checkRequestVariable( id, WORKSPACE_ID ) ) {
            return false;
        }

        return true;
    }
}
