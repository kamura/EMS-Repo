<import resource="classpath:alfresco/extension/js/json2.js">
<import resource="classpath:alfresco/extension/js/utils.js">

//var europaSite = siteService.getSite("europa").node;
var modelFolder = companyhome.childByNamePath("ViewEditor/model");

var modelMapping = {};

function updateOrCreateComment(comment) {
	var commentNode = modelFolder.childByNamePath(comment.id);
	if (commentNode == null) {
		commentNode = modelFolder.createNode(comment.id, "view:Comment");
		commentNode.properties["view:mdid"] = comment.id;
	}
	commentNode.properties["view:documentation"] = comment.body;
	commentNode.properties["view:author"] = comment.author;
	var modified = utils.fromISO8601(comment.modified.replace(" ", "T") + ".000Z");
	commentNode.properties["view:lastModified"] = modified;
	commentNode.properties["view:deleted"] = false;
	commentNode.properties["view:committed"] = true;
	commentNode.save();
	modelMapping[comment.id] = commentNode;
}

function doView(viewid, comments) {
	var viewnode = modelFolder.childByNamePath(viewid);
	if (viewnode == null) {
		status.code = 404;
		return;
	}
	curcomments = viewnode.assocs["view:comments"];
	for (var i in curcomments) {
		var commentNode = curcomments[i];
		if (commentNode.properties["view:mdid"] in modelMapping) 
			continue;//notfound?
		if (commentNode.properties["view:committed"])
			commentNode.properties['view:deleted'] = true;
		commentNode.save();
	}
	for (var i in comments) {
		var commentNode = modelMapping[comments[i]];
		try {
			viewnode.createAssociation(commentNode, "view:comments");
		} catch(exception) {
			//will throw exception when associaiton already exists
		}
	}
}

function main() {
	var postjson = JSON.parse(json.toString());
	if (postjson == null || postjson == undefined)
		return;
	for (var i in postjson.comments) {
		updateOrCreateComment(postjson.comments[i]);
	}
	for (var viewid in postjson.view2comment) {
		doView(viewid, postjson.view2comment[viewid]);
	}
}
status.code = 200;
main();
model['res'] = status.code == 200 ? "ok" : "NotFound";