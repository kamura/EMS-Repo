<import resource="classpath:alfresco/extension/js/json2.js">
<import resource="classpath:alfresco/extension/js/utils.js">
<import resource="classpath:alfresco/extension/js/artifact_utils.js">

var modelFolder = companyhome.childByNamePath("ViewEditor/model");
var elements = [];
var seen = [];
var views = [];
var view2view = {};
var snapshots = [];

var viewid = url.extension
var product = false;

function addElement(modelNode) {
	var info = {};
	info['mdid'] = modelNode.properties["view:mdid"];
	info['documentation'] = fixArtifactUrls(modelNode.properties["view:documentation"], false);
	var name = modelNode.properties["view:name"];
	if (name != null && name != undefined)
		info['name'] = name;
	var dvalue = modelNode.properties["view:defaultValue"];
	if (dvalue != null && dvalue != undefined)
		info['dvalue'] = dvalue;
	elements.push(info);
	seen.push(modelNode.properties['view:mdid']);
}


function handleView(view) {
	var sourcesJson = view.properties["view:sourcesJson"];
	var sources = JSON.parse(sourcesJson);
	for (var i in sources) {
		var sourceid = sources[i];
		var modelNode = modelFolder.childrenByXPath("*[@view:mdid='" + sourceid + "']");
		if (modelNode == null || modelNode == undefined)
			continue;
		if (seen.indexOf(sourceid) >= 0)
			continue;
		addElement(modelNode[0]);
	}
	
	var viewinfo = {};
	viewinfo['mdid'] = view.properties['view:mdid'];
	viewinfo['noSection'] = view.properties['view:noSection'];
	viewinfo['contains'] = JSON.parse(fixArtifactUrls(view.properties['view:containsJson'],true));
	
	var viewcomments = [];
	var comments = view.assocs['view:comments'];
	for (var i in comments) {
		var comment = comments[i];
		if (comment.properties['view:deleted'])
			continue;
		var commentinfo = {};
		commentinfo['author'] = comment.properties['view:author'];
		commentinfo['modified'] = utils.toISO8601(comment.properties['view:lastModified']);
		commentinfo['id'] = comment.properties["view:mdid"];
		commentinfo['body'] = comment.properties['view:documentation'];
		viewcomments.push(commentinfo);
	}
	viewinfo['comments'] = viewcomments.sort(function(a,b) {
		if (a.modified < b.modified)
			return -1;
		if (a.modified == b.modified)
			return 0;
		return 1;
	});
	
	viewinfo['author'] = view.properties['view:author'];
	viewinfo['modified'] = utils.toISO8601(view.properties['view:lastModified']);
	views.push(viewinfo);
	
	if (!product) {
		view2view[view.properties['view:mdid']] = JSON.parse(view.properties['view:viewsJson']);
		var childViews = view.assocs["view:views"];
		for (var i in childViews) {
			handleView(childViews[i]);
		}
	}
	return viewinfo;
}

function getSnapshots(topview) {
	var ss = topview.assocs["view:snapshots"];
	for (var i in ss) {
		utils.toISO8601(date);
		var snapshot = ss[i];
		snapshots.push({
			"id": snapshot.properties["cm:name"], 
			"created": utils.toISO8601(snapshot.properties["cm:created"])
		});
	}
	snapshots.sort(function(a,b) {
		if (a.created < b.created)
			return -1;
		if (a.created == b.created)
			return 0;
		return 1;
	})
}

function main() {
	var topview = modelFolder.childrenByXPath("*[@view:mdid='" + viewid + "']");
	if (topview == null || topview.length == 0) {
		status.code = 404;
	} else {
		topview = topview[0];
		if (topview.properties["view:product"])
			product = true;
		if (product) {
			view2view = JSON.parse(topview.properties["view:view2viewJson"]);
			var noSections = JSON.parse(topview.properties["view:noSectionsJson"]);
			for (var viewmdid in view2view) {
				var view = modelFolder.childrenByXPath("*[@view:mdid='" + viewmdid + "']");
				if (view == null || view.length == 0) {
					status.code = 404;
					return;
				}
				var viewinfo = handleView(view[0]);
				if (noSections.indexOf(viewmdid) >= 0)
					viewinfo.noSection = true;
				else
					viewinfo.noSection = false;
			}
		} else {
			handleView(topview);
		}
		getSnapshots(topview);
	}
}

status.code = 200;
main();

var info = {};
info['elements'] = elements;
info['view2view'] = view2view;
info['views'] = views;
info['rootView'] = viewid;
info['user'] = person.properties['cm:userName'];
info['snapshots'] = snapshots;

var	response = status.code == 200 ? toJson(info) : "NotFound";
if (status.code != 200) {
	status.redirect = true;
	status.message = response;
}
model['res'] = response;
