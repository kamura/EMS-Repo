/*******************************************************************************
 * Copyright (c) <2013>, California Institute of Technology ("Caltech").  
 * 
 * U.S. Government sponsorship acknowledged.
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without modification, are 
 * permitted provided that the following conditions are met:
 * 
 *  - Redistributions of source code must retain the above copyright notice, this list of 
 *    conditions and the following disclaimer.
 *  - Redistributions in binary form must reproduce the above copyright notice, this list 
 *    of conditions and the following disclaimer in the documentation and/or other materials 
 *    provided with the distribution.
 *  - Neither the name of Caltech nor its operating division, the Jet Propulsion Laboratory, 
 *    nor the names of its contributors may be used to endorse or promote products derived 
 *    from this software without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS 
 * OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY 
 * AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER  
 * OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR 
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR 
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON 
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE 
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE 
 * POSSIBILITY OF SUCH DAMAGE.
 ******************************************************************************/

package gov.nasa.jpl.view_repo.webscripts;

import gov.nasa.jpl.view_repo.util.Acm;
import gov.nasa.jpl.view_repo.util.EmsScriptNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import javax.servlet.http.HttpServletResponse;

import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.security.PermissionService;
import org.alfresco.service.cmr.site.SiteInfo;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.extensions.webscripts.Cache;
import org.springframework.extensions.webscripts.Status;
import org.springframework.extensions.webscripts.WebScriptRequest;

/**
 * Descriptor file: /view-repo/src/main/amp/config/alfresco/extension/templates/webscripts/gov/nasa/jpl/javawebscripts/model.post.desc.xml
 * @author cinyoung
 * 
 * TODO Need merge? and force? similar to View?
 *
 */
public class ModelPost extends AbstractJavaWebScript {
	private EmsScriptNode projectNode = null;
	
	private final String ELEMENTS = "elements";
	
	/**
	 * JSONObject of element hierarchy
	 *   	{
  	 *			elementId: [childElementId, ...],
  	 *			...
  	 *	},
	 */
	private JSONObject elementHierarchyJson;
	
	/**
	 * JSONObject of the relationships
		"relationshipElements": {
			relationshipElementId: {"source": sourceElementId, "target": targetElementId},
			...
		},
		"propertyTypes": {
			propertyElementId: typeElementId, //for Property to the property type
			...
		},
		"elementValues": {
			propertyElementId: [elementId], //for property with ElementValue as value types, the value is a noderef
			...
		}
	*/
	private JSONObject relationshipsJson;
	
	/**
	 * Create or update the model as necessary based on the request
	 * @param req			Request used to create/update the model
	 * @param status		Status to be updated
	 * @throws JSONException	Parse error
	 */
	private void createOrUpdateModel(WebScriptRequest req, Status status) throws JSONException {
		// clear out the response cache first (only one instance of each webscript)
		clearCaches();

		JSONObject postJson = (JSONObject)req.parseContent();

		// check if we have single element or array of elements and create accordingly
		if (!postJson.has(ELEMENTS)) {
			updateOrCreateElement(postJson);
		} else {
			// handle the elements and build up the relationship maps
			Object object = postJson.get(ELEMENTS);
			jwsUtil.splitTransactions(new JwsFunctor() {
				@Override
				public Object execute(JSONObject jsonObject, String key,
						Boolean... flags) throws JSONException {
					return null;
				}
				@Override
				public Object execute(JSONArray jsonArray, int index, Boolean... flags) throws JSONException {
					updateOrCreateElement(jsonArray.getJSONObject(index));
					return null;
				}
			}, object);
		}
		
		// handle the hierarchy
		jwsUtil.splitTransactions(new JwsFunctor() {
			@Override
			public Object execute(JSONObject jsonObject, String key,
					Boolean... flags) throws JSONException {
				updateOrCreateElementHierarchy(jsonObject, key);
				return null;
			}
			@Override
			public Object execute(JSONArray jsonArray, int index, Boolean... flags) throws JSONException {
				// do nothing, not called
				return null;
			}
		}, elementHierarchyJson);

		// handle the relationships
		// TODO split this out updating the separate relationships underneath
		updateOrCreateRelationships(relationshipsJson, "relationshipElements");
		updateOrCreateRelationships(relationshipsJson, "propertyTypes");
		updateOrCreateRelationships(relationshipsJson, "elementValues");
	}
	
	/**
	 * Update or create relationships
	 * @param jsonObject	Input data to generate relationships from
	 * @param key			The relationship type (e.g., relationshipElements, projectTypes, or elementValues)
	 * @throws JSONException
	 */
	protected void updateOrCreateRelationships(JSONObject jsonObject, String key) throws JSONException {
		JSONObject object = jsonObject.getJSONObject(key);
		Iterator<?> ids = object.keys();
		while (ids.hasNext()) {
			String id = (String)ids.next();
			if (key.equals("relationshipElements")) {
				updateOrCreateRelationship(object.getJSONObject(id), id);
			} else if (key.equals("propertyTypes")) {
				updateOrCreatePropertyType(object.getString(id), id);
			} else if (key.equals("elementValues")) {
				updateOrCreateElementValues(object.getJSONArray(id), id);
			}
		}
	}

	/**
	 * Update or create element values (multiple noderefs ordered in a list)
	 * @param jsonArray		Array of the IDs that house the values for the element
	 * @param id			The ID of the element to add the values to
	 * @throws JSONException
	 */
	private void updateOrCreateElementValues(JSONArray jsonArray, String id) throws JSONException {
		// create an array of the values to be added in as the elementValue property
		ArrayList<NodeRef> values = new ArrayList<NodeRef>();
		for (int ii = 0; ii < jsonArray.length(); ii++) {
			String valueId = jsonArray.getString(ii);
			EmsScriptNode value = findScriptNodeByName(valueId);
			if (value != null && checkPermissions(value, PermissionService.WRITE)) {
				values.add(value.getNodeRef());
			} else {
				log(LogLevel.ERROR, "could not find element value node with id " + valueId + "\n", HttpServletResponse.SC_BAD_REQUEST);
			}
		}
		
		// only change if old list is different than new
		EmsScriptNode element = findScriptNodeByName(id);
		if (element != null && checkPermissions(element, PermissionService.WRITE)) {
			@SuppressWarnings("unchecked")
			ArrayList<NodeRef> oldValues = (ArrayList<NodeRef>)element.getProperty(Acm.ACM_ELEMENT_VALUE);
			if (!EmsScriptNode.checkIfListsEquivalent(values, oldValues)) {
				element.setProperty(Acm.ACM_ELEMENT_VALUE, values);
			}
		} else {
			log(LogLevel.ERROR, "could not find element node with id " + id + "\n", HttpServletResponse.SC_BAD_REQUEST);
		}
	}

	/**
	 * Update or create the property type association between an element and its type
	 * @param typeId	ID of the type
	 * @param id		ID of the element
	 */
	private void updateOrCreatePropertyType(String typeId, String id) {
		EmsScriptNode property = findScriptNodeByName(id);
		EmsScriptNode propertyType = findScriptNodeByName(typeId);
		
		if (property != null && propertyType != null) {
		    if (checkPermissions(property, PermissionService.WRITE) && checkPermissions(propertyType, PermissionService.READ)) {
		        property.createOrUpdateAssociation(propertyType, Acm.ACM_TYPE);
		    }
		} else {
			if (property == null) {
				log(LogLevel.ERROR, "could not find property node with id " + id + "\n", HttpServletResponse.SC_BAD_REQUEST);
			}
			if (propertyType == null) {
				log(LogLevel.ERROR, "could not find property type node with id " + typeId + "\n", HttpServletResponse.SC_BAD_REQUEST);
			}
		}
	}

	/**
	 * Update or create the element relationship associations 
	 * @param jsonObject	JSONObject that defines the source and target of the directed relationship
	 * @param id			Id of the directed relationship element
	 * @throws JSONException
	 */
	private void updateOrCreateRelationship(JSONObject jsonObject, String id) throws JSONException {
		String sourceId = jsonObject.getString(Acm.JSON_SOURCE);
		String targetId = jsonObject.getString(Acm.JSON_TARGET);

		EmsScriptNode relationship = findScriptNodeByName(id);
		EmsScriptNode source = findScriptNodeByName(sourceId);
		EmsScriptNode target = findScriptNodeByName(targetId);

		if (relationship != null && source != null && target != null) {
		    if (checkPermissions(relationship, PermissionService.WRITE) && checkPermissions(source, PermissionService.READ) && checkPermissions(target, PermissionService.READ)) {
    			relationship.createOrUpdateAssociation(source, Acm.ACM_SOURCE);
    			relationship.createOrUpdateAssociation(target, Acm.ACM_TARGET);
		    }
		} else {
			if (relationship == null) {
				log(LogLevel.ERROR, "could not find relationship node with id " + id + "\n", HttpServletResponse.SC_BAD_REQUEST);
			}
			if (source == null) {
				log(LogLevel.ERROR, "could not find source node with id " + sourceId + "\n", HttpServletResponse.SC_BAD_REQUEST);
			}
			if (target == null) {
				log(LogLevel.ERROR, "could not find target node with id " + targetId + "\n", HttpServletResponse.SC_BAD_REQUEST);
			}
		}
	}

	/**
	 * Create or update the element hierarchy as specified by the JSON Object. Does reification
	 * of containment
	 * @param jsonObject	Hierarchy of containment
	 * @param key			Name of the parent element
	 * @throws JSONException
	 */
	protected void updateOrCreateElementHierarchy(JSONObject jsonObject, String key) throws JSONException {
		String REIFIED_PKG_SUFFIX = "_pkg";
		String pkgName = key + REIFIED_PKG_SUFFIX;

		JSONArray array = jsonObject.getJSONArray(key);

		// only need to create reified container if it has any elements
		if (array.length() > 0) {
			// this is the element node
			EmsScriptNode node = findScriptNodeByName(key);
			if (node == null) {
				log(LogLevel.ERROR, "could not find element node with id " + key + "\n", HttpServletResponse.SC_BAD_REQUEST);
				return;
			}
			
			if (checkPermissions(node, PermissionService.WRITE)) {
    			// create reified container if it doesn't exist
    			EmsScriptNode parent = findScriptNodeByName(pkgName);  // this is the reified element package
    			if (parent == null) {
    				parent = node.getParent().createFolder(pkgName, Acm.ACM_ELEMENT_FOLDER);
    				node.setProperty(Acm.ACM_ID, key);
    				node.setProperty("cm:name", key);
// TODO: this looks redundant    				node.setProperty(Acm.ACM_NAME, (String)node.getProperty(Acm.ACM_NAME));
    			}
    			if (checkPermissions(parent, PermissionService.WRITE)) { 
        			foundElements.put(pkgName, parent);
        			// make sure element and reified container in same place
        			// node should be accurate if hierarchy is correct
        			if (!parent.getParent().equals(node.getParent())) {
        				parent.move(node.getParent());
        			}
        						
        			// move elements to reified container if not already there
        			for (int ii = 0; ii < array.length(); ii++) {
        				String childName = array.getString(ii);
        				EmsScriptNode child = findScriptNodeByName(childName);
        				if (child == null) {
        					log(LogLevel.ERROR, "could not find child node with id " + childName + "\n", HttpServletResponse.SC_BAD_REQUEST);
        					continue;
        				}
        				if (checkPermissions(child, PermissionService.WRITE)) {
            				if (!child.getParent().equals(parent)) {
            					child.move(parent);
            				}
            				
            				// move reified containers as necessary too
            				EmsScriptNode childPkg = findScriptNodeByName(childName + REIFIED_PKG_SUFFIX);
            				if (childPkg != null && !childPkg.getParent().equals(parent)) {
            					childPkg.move(parent);
            				}
        				}
        			}
        			
        			node.createOrUpdateChildAssociation(parent, Acm.ACM_REIFIED_CONTAINMENT);
    			}
			}
		}
	}

	/**
	 * Update or create element with specified metadata
	 * @param jsonObject	Metadata to be added to element
	 * @param key			ID of element
	 * @throws JSONException
	 */
	protected void updateOrCreateElement(JSONObject elementJson) throws JSONException {
		// TODO check permissions
		String id = elementJson.getString(Acm.JSON_ID);
//		long start = System.currentTimeMillis(); System.out.println("updateOrCreateElement " + id);
		
		// find node if exists, otherwise create
		EmsScriptNode node = findScriptNodeByName(id);
		if (node == null) {
			node = projectNode.createNode(id, Acm.JSON2ACM.get(elementJson.getString(Acm.JSON_TYPE)));
			node.setProperty("cm:name", id);
			node.setProperty(Acm.ACM_ID, id);
			// TODO temporarily set title - until we figure out how to use sysml:name in repository browser
			node.setProperty("cm:title", elementJson.getString("name"));
		}
		
		if (checkPermissions(node, PermissionService.WRITE)){
		    foundElements.put(id, node); // cache the found value
		    
		    // injecting the JSON will return the relationships found
		    node.ingestJSON(elementJson);
		    
            // add the relationships into our maps
            JSONObject relations = EmsScriptNode.filterRelationsJSONObject(elementJson);
		    String keys[] = {"elementValues", "propertyTypes", "relationshipElements"};
		    for (String key: keys) {
		        if (!relationshipsJson.has(key)) {
		            relationshipsJson.put(key, new JSONObject());
		        }
		        if (relations.has(key)) {
    		        JSONObject json = relations.getJSONObject(key);
    		        Iterator<?> iter = json.keys();
    		        while (iter.hasNext()) {
    		            String iterId = (String) iter.next();
    		            relationshipsJson.getJSONObject(key).put(iterId, json.get(iterId));
    		        }
		        }
		    }
		    
		    // TODO: need to fix for Permissions reasons
            if (elementJson.has(Acm.JSON_OWNER)) {
                String owner = elementJson.getString(Acm.JSON_OWNER);
                if (owner != null && !owner.equals("null")) {
                    // if owner is null, leave at project root level
                    if (!elementHierarchyJson.has(owner)) {
                        elementHierarchyJson.put(owner, new JSONArray());
                    }
                    elementHierarchyJson.getJSONArray(owner).put(id);
                }
            }
		}

//		long end = System.currentTimeMillis(); System.out.println("\tTotal: " + (end-start));
	}

	/**
	 * Entry point
	 */
	@Override
	protected Map<String, Object> executeImpl(WebScriptRequest req,
			Status status, Cache cache) {
		Map<String, Object> model = new HashMap<String, Object>();
		clearCaches();
		
		if (validateRequest(req, status)) {
			try {
				createOrUpdateModel(req, status);
			} catch (JSONException e) {
				log(LogLevel.ERROR, "JSON malformed\n", HttpServletResponse.SC_BAD_REQUEST);
				e.printStackTrace();
			}
		}

		model.put("res", response.toString());
		return model;
	}
	
	
	@Override
	protected boolean validateRequest(WebScriptRequest req, Status status) {
		if (!checkRequestContent(req)) {
			return false;
		}
		
		String elementId = req.getServiceMatch().getTemplateVars().get("elementid");
		if (elementId != null) {
			if (!checkRequestVariable(elementId, "elementid")) {
				return false;
			}
			
			// projectNode should be the owner..., which should exist
			try {
				JSONObject postJson = (JSONObject)req.parseContent();
				JSONObject elementsJson = postJson.getJSONObject("elements");
				JSONObject elementJson = elementsJson.getJSONObject(elementId);
				projectNode = findScriptNodeByName(elementJson.getString(Acm.JSON_OWNER));
			} catch (JSONException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			
			if (projectNode == null) {
				return false;
		 	}
			
			if (checkPermissions(projectNode, PermissionService.WRITE)) {
			    return false;
			}
		} else {
			String siteName = req.getServiceMatch().getTemplateVars().get(SITE_NAME);
			if (!checkRequestVariable(siteName, SITE_NAME)) {
				return false;
			} 
	
			String projectId = req.getServiceMatch().getTemplateVars().get(PROJECT_ID);
			if (!checkRequestVariable(projectId, PROJECT_ID)) {
				return false;
			}
	
			SiteInfo siteInfo = services.getSiteService().getSite(siteName);
			if (!checkRequestVariable(siteInfo, "Site")) {
				return false;
			}
					
			if (!checkPermissions(siteInfo.getNodeRef(), PermissionService.WRITE)) {
				return false;
			}
	
			EmsScriptNode siteNode = getSiteNode(siteName);
			projectNode = siteNode.childByNamePath("/ViewEditor/" + projectId);
			if (projectNode == null) {
				log(LogLevel.ERROR, "Project not found.\n", HttpServletResponse.SC_NOT_FOUND);
				return false;
			}
		}
		
		return true;
	}
	
	@Override
	protected void clearCaches() {
		super.clearCaches();
		elementHierarchyJson = new JSONObject();
		relationshipsJson = new JSONObject();
	}
}