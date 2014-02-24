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

import gov.nasa.jpl.view_repo.util.Acm;
import gov.nasa.jpl.view_repo.util.EmsScriptNode;

import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.HttpServletResponse;

import org.alfresco.service.cmr.security.PermissionService;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONArray;
import org.springframework.extensions.webscripts.Cache;
import org.springframework.extensions.webscripts.Status;
import org.springframework.extensions.webscripts.WebScriptRequest;

public class ViewPost extends AbstractJavaWebScript {
	@Override
	protected boolean validateRequest(WebScriptRequest req, Status status) {
		// TODO Auto-generated method stub
		return false;
	}
	
	
	@Override
	protected synchronized Map<String, Object> executeImpl(WebScriptRequest req,
			Status status, Cache cache) {
		clearCaches();
		
		Map<String, Object> model = new HashMap<String, Object>();
		
		try {
			updateViews((JSONObject)req.parseContent());
		} catch (JSONException e) {
			log(LogLevel.ERROR, "JSON parse exception: " + e.getMessage(), HttpServletResponse.SC_BAD_REQUEST);
			e.printStackTrace();
		}
		
        status.setCode(responseStatus.getCode());
		model.put("res", response.toString());
		return model;
	}
	
	private void updateViews(JSONObject jsonObject) throws JSONException {
		if (jsonObject.has("views")) {
			JSONArray viewsJson = jsonObject.getJSONArray("views");
			
			for (int ii = 0; ii < viewsJson.length(); ii++) {
			    updateView(viewsJson, ii);
			}
			
//			jwsUtil.splitTransactions(new JwsFunctor() {
//				@Override
//				public Object execute(JSONArray jsonArray, int index,
//						Boolean... flags) throws JSONException {
//					updateView(jsonArray, index);
//					return null;
//				}
//			}, viewsJson);
		}
	}
	
	
	private void updateView(JSONArray viewsJson, int index) throws JSONException {
		JSONObject viewJson = viewsJson.getJSONObject(index);
		updateView(viewJson);
	}
	
	private void updateView(JSONObject viewJson) throws JSONException {
		String id = viewJson.getString(Acm.JSON_ID);
		if (id == null) {
			log(LogLevel.ERROR, "view id not specified.\n", HttpServletResponse.SC_BAD_REQUEST);
			return;
		}
		
		EmsScriptNode view = findScriptNodeByName(id);
		if (view == null) {
			log(LogLevel.ERROR, "could not find view with id: " + id, HttpServletResponse.SC_NOT_FOUND);
			return;
		}
			
		if (checkPermissions(view, PermissionService.WRITE)) {
		    view.createOrUpdateAspect(Acm.ACM_VIEW);
		    view.ingestJSON(viewJson);
		}
	}
}
