/**
 * 
 */
package gov.nasa.jpl.view_repo.webscripts;

import gov.nasa.jpl.mbee.util.TimeUtils;
import gov.nasa.jpl.mbee.util.Timer;
import gov.nasa.jpl.mbee.util.Utils;
import gov.nasa.jpl.view_repo.util.EmsScriptNode;
import gov.nasa.jpl.view_repo.util.NodeUtil;
import gov.nasa.jpl.view_repo.util.WorkspaceNode;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletResponse;

import org.alfresco.repo.model.Repository;
import org.alfresco.repo.security.authentication.AuthenticationUtil;
import org.alfresco.service.ServiceRegistry;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.version.Version;
import org.alfresco.service.cmr.version.VersionHistory;
import org.alfresco.service.cmr.version.VersionService;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.extensions.webscripts.Cache;
import org.springframework.extensions.webscripts.Status;
import org.springframework.extensions.webscripts.WebScriptRequest;

/**
 * @author dank
 *
 */
public class HistoryGet extends ModelGet {

    static Logger logger = Logger.getLogger(HistoryGet.class);

    /**
     * 
     */
    public HistoryGet() {
        // TODO Auto-generated constructor stub
        super();
    }

    /**
     * 
     * @param repositoryHelper
     * @param registry
     */
    public HistoryGet(Repository repositoryHelper, ServiceRegistry registry) {
        super(repositoryHelper, registry);
    }

    /**
     * 
     * @param ref
     * @return
     */
    public JSONArray getAllHistory(NodeRef ref) {
        JSONArray jsonAr = null;
        VersionHistory history = null;
        // Tries to to get the version history based on the NodeRef that is
        // passed into the
        // getVersionHistory method.
        try {
            VersionService versionService = getServices().getVersionService();
            if (versionService.isVersioned( ref )) {
                history = versionService.getVersionHistory(ref);
            }
        } catch (Exception error) {
            logger.error(error.getMessage());
        }
        if (history == null) {
            logger.warn("couldn't get history for: " + ref);
            return jsonAr;
        }
        
        Collection<Version> versions = history.getAllVersions();

        if (versions != null) {
            jsonAr = new JSONArray(versions.toArray());
        }

        return jsonAr;
    }

    protected Map<String, Object> executeImpl(WebScriptRequest req, Status status, Cache cache) {
        HistoryGet instance = new HistoryGet(repository, getServices());
        return instance.executeImplImpl(req, status, cache, runWithoutTransactions);
    }

    
    protected Map<String, Object> executeImplImpl(WebScriptRequest req, Status status, Cache cache) {
        int index;
        Map<String, Object> model = new HashMap<String, Object>();

        Timer timer = new Timer();
        String user = AuthenticationUtil.getFullyAuthenticatedUser();
        printHeader(user, logger, req);

        // make sure to pass down view request flag to instance
        setIsViewRequest(isViewRequest);

        // Creates a JSON object of NodeUtil which is passed into handleRquest along with the request - 'req'. 
        //     HandleRequest will find all NodeRefs that belong with the request then search for all version histories of that
        //    element. It will returns a JSONArray to be parsed for the keys versionLabel and frozenCreated. The values
        //    associated with these keys will be put to a json object that is a appended to an array. Once all values have
        //    been extracted, it maps the array and returns the Map.
        JSONObject top = NodeUtil.newJsonObject();
        JSONArray elementsJson = handleRequest(req, top);

        try {
            JSONObject obj = new JSONObject();
            top = new JSONObject();
            
            // Searches for each key and value to be appended to the json array.
            for (index = 0; index < elementsJson.length(); index++) {
                if (logger.isDebugEnabled()) logger.debug(NodeUtil.jsonToString(top, 4));
                if (logger.isDebugEnabled()) logger.debug(elementsJson.getJSONObject(index).getJSONObject("versionProperties").toString(4));
                obj.put("label",
                        elementsJson.getJSONObject(index).getJSONObject("versionProperties").get("versionLabel"));
                //Tue Nov 10 11:25:58 PST 2015
                SimpleDateFormat sdf = new SimpleDateFormat("EEE MMM dd HH:mm:ss zzz yyyy");
                try {
                    String time = (String)elementsJson.getJSONObject(index).getJSONObject("versionProperties").get("frozenModified");
                    Date timestamp = sdf.parse( time );
                    obj.put("timestamp",
                            TimeUtils.toTimestamp( timestamp ));
                } catch ( ParseException e ) {
                    e.printStackTrace();
                }
                obj.put("modifier",
                        elementsJson.getJSONObject(index).getJSONObject("versionProperties").get("frozenModifier"));
                top.append("versions", obj);
                obj = new JSONObject();
            }

            if (!Utils.isNullOrEmpty(response.toString()))
                top.put("message", response.toString());
            if (prettyPrint) {
                model.put("res", NodeUtil.jsonToString(top, 4));
            } else {
                model.put("res", NodeUtil.jsonToString(top));
            }
        } catch (JSONException e) {
            log(Level.ERROR, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Could not create JSONObject");
            model.put("res", createResponseJson());
            e.printStackTrace();
        }

        status.setCode(responseStatus.getCode());
        printFooter(user, logger, timer);

        return model;
    }

    @Override
    protected boolean validateRequest(WebScriptRequest req, Status status) {
        // TODO Auto-generated method stub
        return true;
    }

    /**
     * Wrapper for handling a request and getting the appropriate JSONArray of
     * elements
     * 
     * @param req
     * @param top
     * @return
     */
    protected JSONArray handleRequest(WebScriptRequest req, final JSONObject top) {
        // REVIEW -- Why check for errors here if validate has already been
        // called? Is the error checking code different? Why?

        // Creates an empty JSONArray
        JSONArray jsonHist = null;
        try {
            String[] idKeys = { "modelid", "elementid", "elementId" };
            String modelId = null;
            for (String idKey : idKeys) {
                modelId = req.getServiceMatch().getTemplateVars().get(idKey);
                if (modelId != null) {
                    break;
                }
            }

            if (null == modelId) {
                logger.error("Model ID Null...");
                log(Level.ERROR, HttpServletResponse.SC_NOT_FOUND, "Could not find element %s", modelId);
                return new JSONArray();
            }

            // get timestamp if specified
            String timestamp = req.getParameter("timestamp");
            Date dateTime = TimeUtils.dateFromTimestamp(timestamp);

            WorkspaceNode workspace = getWorkspace(req);

            // see if prettyPrint default is overridden and change
            prettyPrint = getBooleanArg(req, "pretty", prettyPrint);

            Long depth = 0l;

            if (logger.isDebugEnabled())
                logger.debug("modelId = " + modelId);
            boolean findDeleted = depth == 0 ? true : false;
            EmsScriptNode modelRootNode = findScriptNodeById(modelId, workspace, dateTime, findDeleted);
            if (logger.isDebugEnabled())
                logger.debug("modelRootNode = " + modelRootNode);

            if (modelRootNode == null) {
                log(Level.ERROR, HttpServletResponse.SC_NOT_FOUND, "Element %s notfound",
                        modelId + (dateTime == null ? "" : " at " + dateTime));
                return new JSONArray();
            } else if (modelRootNode.isDeleted()) {
                log(Level.DEBUG, HttpServletResponse.SC_GONE, "Element exists, but is deleted.");
                return new JSONArray();
            }

            // returns a JSONArray of versionedNodeRef JSONObjects 
            jsonHist = getAllHistory(modelRootNode.getNodeRef());

        } catch (JSONException e) {
            e.printStackTrace();
        }

        return jsonHist;
    }
}
