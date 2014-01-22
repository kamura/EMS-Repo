/*******************************************************************************
 * Copyright (c) <2013>, California Institute of Technology ("Caltech").  
 * U.S. Government sponsorship acknowledged.
 * 
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

import gov.nasa.jpl.view_repo.util.EmsScriptNode;
import gov.nasa.jpl.view_repo.util.Acm;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.servlet.http.HttpServletResponse;

import org.alfresco.service.cmr.security.PermissionService;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.extensions.webscripts.Cache;
import org.springframework.extensions.webscripts.Status;
import org.springframework.extensions.webscripts.WebScriptRequest;

/**
 * Mother-Of-All-Product gets everything about a product in one big blob.
 * @author cinyoung
 *
 */
public class MoaProductGet extends AbstractJavaWebScript {
	private JSONObject productsJson;
	private JSONArray viewsJson;
	private JSONArray elementsJson;
	
	@Override
	protected boolean validateRequest(WebScriptRequest req, Status status) {
		String productId = req.getServiceMatch().getTemplateVars().get("id");
		if (!checkRequestVariable(productId, "id")) {
			return false;
		}
		
		EmsScriptNode product = findScriptNodeByName(productId);
		if (product == null) {
			log(LogLevel.ERROR, "Product not found with id: " + productId + ".\n", HttpServletResponse.SC_NOT_FOUND);
			return false;
		}
		
		if (!checkPermissions(product, PermissionService.READ)) {
			return false;
		}
		
		return true;
	}
	
	@Override
	protected void clearCaches() {
		super.clearCaches();
		productsJson = new JSONObject();
		viewsJson = new JSONArray();
		elementsJson = new JSONArray();
	}
	
	@Override
	protected Map<String, Object> executeImpl(WebScriptRequest req,
			Status status, Cache cache) {
		clearCaches();
		
		Map<String, Object> model = new HashMap<String, Object>();
		
		if (validateRequest(req, status)) {
			try {
				String productId = req.getServiceMatch().getTemplateVars().get("id");
				handleProduct(productId);
			} catch (JSONException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		
		if (responseStatus.getCode() == HttpServletResponse.SC_OK) {
			try {
				model.put("res", productsJson.toString(4));
			} catch (JSONException e) {
				e.printStackTrace();
				log(LogLevel.ERROR, "JSON creation error", HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
				model.put("res", response.toString());
			}
		} else {
			model.put("res", response.toString());
		}

		status.setCode(responseStatus.getCode());
		return model;
	}

	
	private void handleProduct(String productId) throws JSONException {
		EmsScriptNode product = findScriptNodeByName(productId);
		if (product == null) {
			log(LogLevel.ERROR, "Product not found with ID: " + productId, HttpServletResponse.SC_NOT_FOUND);
		}

		if (checkPermissions(product, PermissionService.READ)){
		    JSONObject object = product.toJSONObject(Acm.JSON_TYPE_FILTER.PRODUCT);
		    productsJson = new JSONObject(object, JSONObject.getNames(object));
		    
		    handleViews(object.getJSONArray(Acm.JSON_VIEW_2_VIEW));
		    
		    productsJson.put("views", viewsJson);
		    productsJson.put("elements", elementsJson);
		}
	}
	
	private void handleViews(JSONArray view2view) throws JSONException {
	    Set<String> viewIds = new HashSet<String>();
	    Set<String> elementIds = new HashSet<String>();
	    
	    // find all the views
	    for (int ii = 0; ii < view2view.length(); ii++) {
	        JSONObject view = view2view.getJSONObject(ii);
	        viewIds.add(view.getString(Acm.JSON_ID));
	        JSONArray childrenViews = view.getJSONArray(Acm.JSON_CHILDREN_VIEWS);
	        for (int jj = 0; jj < childrenViews.length(); jj++) {
	            viewIds.add(childrenViews.getString(jj));
	        }
	    }
	    
	    // insert all the views and find all the elements
	    for (String viewId: viewIds) {
	        EmsScriptNode view = findScriptNodeByName(viewId);
	        if (view != null && checkPermissions(view, PermissionService.READ)) {
    	        JSONObject viewJson = view.toJSONObject(Acm.JSON_TYPE_FILTER.VIEW);
    	        
    	        // add any related comments as part of the view
    	        JSONArray commentsJson = new JSONArray();
    	        JSONArray comments = view.getSourceAssocsByType(Acm.ACM_ANNOTATED_ELEMENTS);
    	        for (int ii = 0; ii < comments.length(); ii++) {
    	            EmsScriptNode comment = findScriptNodeByName(comments.getString(ii));
    	            if (comment != null && checkPermissions(comment, PermissionService.READ)) {
    	                commentsJson.put(comment.toJSONObject(Acm.JSON_TYPE_FILTER.COMMENT));
    	            }
    	        }
    	        viewJson.put("comments", commentsJson);
    	        
    	        viewsJson.put(viewJson);
    	        JSONArray allowedElements = viewJson.getJSONArray(Acm.JSON_ALLOWED_ELEMENTS);
    	        for (int ii = 0; ii < allowedElements.length(); ii++) {
    	            elementIds.add(allowedElements.getString(ii));
    	        }
	        }
	    }
	    
	    // insert all the elements
	    for (String elementId: elementIds) {
	        EmsScriptNode element = findScriptNodeByName(elementId);
	        if (element != null && checkPermissions(element, PermissionService.READ)) {
	            JSONObject elementJson = element.toJSONObject(Acm.JSON_TYPE_FILTER.ELEMENT);
	            elementsJson.put(elementJson);
	        }
	    }
	}
}
