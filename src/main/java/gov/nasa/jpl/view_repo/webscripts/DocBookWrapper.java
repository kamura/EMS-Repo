package gov.nasa.jpl.view_repo.webscripts;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.alfresco.model.ContentModel;
import org.alfresco.repo.content.MimetypeMap;
import org.alfresco.service.ServiceRegistry;
import org.alfresco.service.cmr.repository.ContentReader;
import org.alfresco.service.cmr.repository.ContentService;
import org.alfresco.service.cmr.repository.ContentWriter;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.search.ResultSet;
import org.alfresco.util.TempFileProvider;
import org.alfresco.util.exec.RuntimeExec;
import org.alfresco.util.exec.RuntimeExec.ExecutionResult;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import gov.nasa.jpl.docbook.model.DBBook;
import gov.nasa.jpl.docbook.model.DBSerializeVisitor;
import gov.nasa.jpl.view_repo.DocBookContentTransformer;
import gov.nasa.jpl.view_repo.actions.ActionUtil;
import gov.nasa.jpl.view_repo.util.EmsScriptNode;
import gov.nasa.jpl.view_repo.util.NodeUtil;

public class DocBookWrapper {
	public static final String DOC_BOOK_DIR_NAME = "docbook";
	
	private DBBook dbBook;
	private Path docGenCssFileName;
	private Path dbDirName;
	private Path dbFileName;
	private DBSerializeVisitor dbSerializeVisitor;
	private String content;
	private Path fobFileName;
	private Path fobXslFileName;
	private Path htmlXslFileName;
	private Path imageDirName;
	private Path jobDirName;
	private String snapshotName;
	private EmsScriptNode snapshotNode;
	private Path xalanDirName;
	
	public DocBookWrapper(String snapshotName, EmsScriptNode snapshotNode){
		this.snapshotName = snapshotName;
		this.snapshotNode = snapshotNode;
		this.dbSerializeVisitor = new DBSerializeVisitor(true, null);
		setPaths();
	}

	private boolean createDocBookDir(){
		boolean bSuccess = true;
		if(!Files.exists(this.dbDirName)){
    		if(!new File(this.dbDirName.toString()).mkdirs()) {
    			bSuccess = false;
    			System.out.println("Failed to create DocBook Directory: " + this.dbDirName);
    		}
    	}
		return bSuccess;
	}

	/**
	 * Helper to execute the command using RuntimeExec
	 * 
	 * @param srcFile	File to transform
	 * @return 			Absolute path of the generated file
	 * @throws Exception 
	 */
	private String doPDFTransformation(File srcFile) throws Exception {
		RuntimeExec re = new RuntimeExec();
		List<String> command = new ArrayList<String>();
		
		System.out.println("srcFile: " + srcFile.getAbsolutePath());
		String source = srcFile.getAbsolutePath();
		String target = source.subSequence(0, source.lastIndexOf(".")) + ".pdf";
		command.add(this.getFobFileName());
		command.add("-xml");
		command.add(source);
		command.add("-xsl");
		command.add(this.getFobXslFileName());
		command.add("-pdf");
		command.add(target);

		System.out.println("DO_TRANSFORM source: " + source);
		System.out.println("DO_TRANSFORM target: " + target);
		System.out.println("DO_TRANSFROM cmd: " + command);
		
		re.setCommand(list2Array(command));
		ExecutionResult result = re.execute();

		if (!result.getSuccess()) {
			System.out.println("FOP transformation command unsuccessful\n");
			//logger.error("FOP transformation command unsuccessful\n");
			//System.out.println("Exit value: " + result.getExitValue());
			throw new Exception("FOP transformation command failed! Exit value: " + result.getExitValue());
		}

		return target;
	}

    /**
	 * Utility function to find all the NodeRefs for the specified name
	 * @param name
	 * @return
	 */
	private ResultSet findNodeRef(String name) {
	    String pattern = "@cm\\:name:\"" + name + "\"";
		ResultSet query = NodeUtil.luceneSearch( pattern);
		return query;
	}
	
	private String formatContent(String rawContent){
		Document document = Jsoup.parseBodyFragment(rawContent);
		Elements lits = document.getElementsByTag("literallayout");
		for(Element lit : lits){
			Elements cirRefs = lit.getElementsByTag("CircularReference");
			for(Element cirRef : cirRefs){
				//cirRef.before("</literallayout><font color='red'>Circular Reference!</font><literallayout>");
				//cirRef.remove();
			}
		}
		return document.body().html();
	}
	
	public String getContent(){
		//this.dbBook.accept(this.dbSerializeVisitor);
		if(this.content == null || this.content.isEmpty()){
			this.dbSerializeVisitor.visit(dbBook);
			String rawContent = this.dbSerializeVisitor.getOut(); 
			//this.content = formatContent(rawContent);
			this.content = rawContent;
		}
		return this.content;
	}

	public String getDBFileName(){
		return this.dbFileName.toString();
	}

	public String getDBDirImage(){
		return this.imageDirName.toString();
	}
	
	public String getDBDirName(){
		return this.dbDirName.toString();
	}

	public String getDocGenCssFileName(){
		return this.docGenCssFileName.toString();
	}
	
	public String getFobFileName(){
		return this.fobFileName.toString();
	}
	
	public String getFobXslFileName(){
		return this.fobXslFileName.toString();
	}
	
	public String getHtmlXslFileName(){
		return this.htmlXslFileName.toString();
	}

	public String getJobDirName(){
		return this.jobDirName.toString();
	}
	
	public EmsScriptNode getSnapshotNode(){
		return this.snapshotNode;
	}
	
	public String getXalanDirName(){
		return this.xalanDirName.toString();
	}
	
	/**
	 * Helper method to convert a list to an array of specified type
	 * @param list
	 * @return
	 */
	private String[] list2Array(List<String> list) {
		return Arrays.copyOf(list.toArray(), list.toArray().length, String[].class);
	}

	private void retrieveDocBook(){
		if(this.snapshotNode.hasAspect("view2:docbook")){
    		//System.out.println("has docbook aspect!");
    		//System.out.println("getting docbook node...");
    		NodeRef dbNodeRef = (NodeRef)this.snapshotNode.getProperty("view2:docbookNode");
    		if(dbNodeRef==null) System.out.println("dbNodeRef is null!");
    		try{
    			retrieveStringPropContent(dbNodeRef, this.dbFileName);
    			System.out.println("retrieved docbook!");
    		}
    		catch(Exception ex){
    			System.out.println("Failed to retrieve DocBook!");
    			ex.printStackTrace();
    		}
    	}
    }
    
	private void retrieveImages(File srcFile, ContentService contentService){
		DocBookContentTransformer dbTransf = new DocBookContentTransformer();
		System.out.println("getting images...");
		// TODO: check image time information with temp file and replace if image has been updated more recently
		for (String img: dbTransf.findImages(srcFile)) 
		{
			String imgFilename = this.getDBDirImage() + File.separator + img;
			File imgFile = new File(imgFilename);
			if (!imgFile.exists()) 
			{
				System.out.println("finding image: " + imgFilename);
				ResultSet rs = findNodeRef(img);
				ContentReader imgReader;
				for (NodeRef nr: rs.getNodeRefs()) {
					System.out.println("retrieving image file...");
					imgReader = contentService.getReader(nr, ContentModel.PROP_CONTENT);
					System.out.println("saving image file...");
					if(!Files.exists(this.imageDirName)){ 
						if(!new File(this.imageDirName.toString()).mkdirs()){
							System.out.println("Failed to create directory for " + this.imageDirName);
						}
					}
					imgReader.getContent(imgFile);		
					break;
				}
			}
		}	
	}
	
    private void retrieveStringPropContent(NodeRef node, Path savePath) throws IOException{
    	if(!Files.exists(savePath.getParent())){
	    	if(!new File(savePath.getParent().toString()).mkdirs()){
	    		System.out.println("Could not create path: " + savePath.getParent());
	    		return;
	    	}
    	}
    	ContentService contentService = this.getSnapshotNode().getServices().getContentService();
    	if(contentService == null) System.out.println("conent service is null!");
		ContentReader reader = contentService.getReader(node, ContentModel.PROP_CONTENT);
		if(reader==null) System.out.println("reader is null!");
		File srcFile = new File(savePath.toString());
		reader.getContent(srcFile);
    }

	public void save() throws IOException{
		String docBookXml = this.getContent();
		new File(this.dbDirName.toString()).mkdirs();
    	File f = new File(this.dbFileName.toString());
    	FileWriter fw = new FileWriter(f);
    	fw.write(docBookXml);
    	fw.close();
	}
	
	public void saveDocBookToRepo(EmsScriptNode snapshotFolder){
		ServiceRegistry services = this.snapshotNode.getServices();
		try{
			EmsScriptNode node = snapshotFolder.createNode(this.snapshotName + "_docbook", "cm:content");
			ActionUtil.saveStringToFile(node, "application/docbook+xml", services, this.getContent());
			if(this.snapshotNode.createOrUpdateAspect("view2:docbook")){
				this.snapshotNode.createOrUpdateProperty("view2:docbookNode", node.getNodeRef());
			}
		}
		catch(Exception ex){
			System.out.println("Failed to create docbook child node!");
			ex.printStackTrace();
		}
	}
	
	public boolean saveFileToRepo(EmsScriptNode scriptNode, String mimeType, String filePath){
		boolean bSuccess = false;
		if(filePath == null || filePath.isEmpty()){
			System.out.println("File path parameter is missing!");
			return false;
		}
		if(!Files.exists(Paths.get(filePath))){
			System.out.println(filePath + " does not exist!");
			return false;
		}
		
		NodeRef nodeRef = scriptNode.getNodeRef();
		ContentService contentService = scriptNode.getServices().getContentService();
		
		ContentWriter writer = contentService.getWriter(nodeRef, ContentModel.PROP_CONTENT, true);
		writer.setLocale(Locale.US);
		File file = new File(filePath);
		writer.setMimetype(mimeType);
		try{
			writer.putContent(file);
			bSuccess = true;
		}
		catch(Exception ex){
			System.out.println("Failed to save '" + filePath + "' to repository!");
		}
		return bSuccess;
	}

	public void saveHtmlZipToRepo(EmsScriptNode snapshotFolder) throws Exception{
		try{
			this.transformToHTML();
			String zipPath = this.zipHtml();
			if(zipPath == null || zipPath.isEmpty()) throw new Exception("Failed to zip HTML files and resources!");
			
			EmsScriptNode node = snapshotFolder.createNode(this.snapshotName + "_HTML_Zip", "cm:content");
			if(node == null) throw new Exception("Failed to create HTML repository node!");
			
			if(!this.saveFileToRepo(node, MimetypeMap.MIMETYPE_ZIP, zipPath)) throw new Exception("Failed to save HTML artifact to repository!");
			if(this.snapshotNode.createOrUpdateAspect("view2:htmlZip")){
				this.snapshotNode.createOrUpdateProperty("view2:htmlZipNode", node.getNodeRef());
			}
		}
		catch(Exception ex){
			throw new Exception("Failed to generate HTML zip!", ex);
		}
	}
	
	public void savePdfToRepo(EmsScriptNode snapshotFolder) throws Exception{
		try{
			String pdfPath = transformToPDF();
			if(pdfPath == null || pdfPath.isEmpty()) throw new Exception("Failed to transform from DocBook to PDF!");
			
			EmsScriptNode node = snapshotFolder.createNode(this.snapshotName + "_PDF", "cm:content");
			if(node == null) throw new Exception("Failed to create PDF repository node!");
			
			if(!this.saveFileToRepo(node, MimetypeMap.MIMETYPE_PDF, pdfPath)) throw new Exception("Failed to save PDF artifact to repository!");
			if(this.snapshotNode.createOrUpdateAspect("view2:pdf")){
				this.snapshotNode.createOrUpdateProperty("view2:pdfNode", node.getNodeRef());
			}
		}
		catch(Exception ex){
			throw new Exception("Failed to genearate PDF!", ex);
		}
	}
	
	public void setDBBook(DBBook dbBook){
		this.dbBook = dbBook;
	}
	
	private void setPaths(){
		String tmpDirName	= TempFileProvider.getTempDir().getAbsolutePath();
    	this.jobDirName = Paths.get(tmpDirName, this.snapshotName);
		this.dbDirName = Paths.get(jobDirName.toString(), "docbook");
		this.imageDirName = Paths.get(dbDirName.toString(), "images");
		this.dbFileName = Paths.get(this.dbDirName.toString(), this.snapshotName + ".xml");
		
		//String docgenDirName = "/opt/local/alfresco/tomcat/webapps/alfresco/docgen/";
		String docgenDirName = "/opt/local/docbookgen/";
		if(!Files.exists(Paths.get(docgenDirName))){
			String userHome = System.getProperty("user.home");
			docgenDirName = Paths.get(userHome, "git/docbookgen").toString();
			if(!Files.exists(Paths.get(docgenDirName))) 
				System.out.println("Failed to find docbookgen/fop directory!");
			else{
				docgenDirName = Paths.get(docgenDirName).toAbsolutePath().toString();
			}
		}
		this.fobFileName = Paths.get(docgenDirName, "fop-1.0", "fop");
		this.fobXslFileName = Paths.get(docgenDirName, "xsl/fo/mgss.xsl");
		this.xalanDirName = Paths.get(docgenDirName, "xalan-j_2_7_1");
		this.htmlXslFileName = Paths.get(docgenDirName, "xsl/html", "chunk_custom.xsl");
		this.docGenCssFileName = Paths.get(docgenDirName, "xsl", "docgen.css");
	}
	
	private void transformToHTML() throws Exception{
		if(!createDocBookDir()) return;
		System.out.println("Retrieving DocBook...");
		retrieveDocBook();
		File srcFile = new File(this.getDBFileName());
		System.out.println("Retrieving images...");
		retrieveImages(srcFile, this.snapshotNode.getServices().getContentService());
		RuntimeExec re = new RuntimeExec();
		List<String> command = new ArrayList<String>();
		
		String source = this.getDBFileName();
		//String target = source.substring(0, source.indexOf(".")) + ".html";
		String xalanDir = this.getXalanDirName();
		String cp = xalanDir + "/xalan.jar:" + xalanDir + "/xercesImpl.jar:" + xalanDir + "/serializer.jar:" +xalanDir + "/xml-apis.jar";
		command.add("java");
		command.add("-cp");
		command.add(cp);
		command.add("org.apache.xalan.xslt.Process");
		command.add("-in");
		command.add(source);
		command.add("-xsl");
		command.add(this.getHtmlXslFileName());
		//command.add("-out");
		//command.add(target);
		command.add("-param");
		command.add("chunk.tocs.and.lots");
		command.add("1");
		command.add("-param");
		command.add("chunk.tocs.and.lots.has.title");
		command.add("1");
		command.add("-param");
		command.add("html.stylesheet");
		command.add("docgen.css");

		//System.out.println("DO_TRANSFORM source: " + source);
		//System.out.println("DO_TRANSFORM target: " + target);
		System.out.println("DO_TRANSFROM cmd: " + command);
		
		re.setCommand(list2Array(command));
		ExecutionResult result = re.execute();
			    		
		if (!result.getSuccess()) {
			System.out.println("Failed HTML transformation!\n");
			//logger.error("FOP transformation command unsuccessful\n");
			throw new Exception("Failed HTML transformation!");
		}
		else{
			String title = "";
			File frame = new File(Paths.get(this.getDBDirName(), "frame.html").toString());
			try{
				BufferedWriter writer = new BufferedWriter(new FileWriter(frame));
				writer.write("<html><head><title>" + title + "</title></head><frameset cols='30%,*' frameborder='1' framespacing='0' border='1'><frame src='bk01-toc.html' name='list'><frame src='index.html' name='body'></frameset></html>");
		        writer.close();
		        Files.copy(Paths.get(this.getDocGenCssFileName()), Paths.get(this.getDBDirName(), "docgen.css"));
			}
			catch(Exception ex){
				throw new Exception("Failed to transform DocBook to HTML!", ex);
			}
		}
	}
	
	private String transformToPDF() throws Exception{
    	if(!createDocBookDir()){
    		System.out.println("Failed to create DocBook directory!");
    		return null;
    	}
    	System.out.println("Retrieving DocBook...");
    	retrieveDocBook();
		ContentService contentService = this.snapshotNode.getServices().getContentService();
    	File srcFile = new File(this.getDBFileName());
    	System.out.println("Retrieving images...");
    	retrieveImages(srcFile, contentService);
		// do transformation then put result into writer
		String targetFilename = doPDFTransformation(srcFile);
		return targetFilename;
	}
	

	public String zipHtml() throws IOException, InterruptedException {
		ProcessBuilder processBuilder = new ProcessBuilder();
		processBuilder.directory(new File(this.getJobDirName()));
		System.out.println("zip working directory: " + processBuilder.directory());
		List<String> command = new ArrayList<String>();
		String zipFile = this.snapshotName + ".zip";
		command.add("zip");
		command.add("-r");
		command.add(zipFile);
		//command.add("\"*.html\"");
		//command.add("\"*.css\"");
		command.add("docbook");
		
		// not including docbook and pdf files
		//command.add("-x");
		//command.add("*.db");
		//command.add("*.pdf");

		processBuilder.command(command);
		System.out.println("zip command: " + processBuilder.command());
		Process process = processBuilder.start();
		int exitCode = process.waitFor();
		if(exitCode != 0){
			System.out.println("zip failed!");
			System.out.println("exit code: " + exitCode);
		}
		
		return Paths.get(this.getJobDirName(), zipFile).toString();
	}
}

