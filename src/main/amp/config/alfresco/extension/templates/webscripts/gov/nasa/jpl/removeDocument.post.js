<import resource="classpath:alfresco/extension/js/json2.js">
<import resource="classpath:alfresco/extension/js/utils.js">

//var europaSite = siteService.getSite("europa").node;
var modelFolder = companyhome.childByNamePath("ViewEditor/model");


function main() {
	var docid = url.templateArgs.docid;
	var dnode = modelFolder.childrenByXPath("*[@view:mdid='" + docid + "']");
	if (dnode == null || dnode.length == 0) {
		status.code = 404;
		return;
	} else
		dnode = dnode[0];	
	cleanDocument(dnode);
}
status.code = 200;
main();
if (status.code == 200)
	model['res'] = "ok";
else
	model['res'] = "NotFound";