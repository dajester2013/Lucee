/**
 * Copyright (c) 2014, the Railo Company Ltd.
 * Copyright (c) 2015, Lucee Assosication Switzerland
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either 
 * version 2.1 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public 
 * License along with this library.  If not, see <http://www.gnu.org/licenses/>.
 * 
 */
package lucee.runtime.extension;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import lucee.Info;
import lucee.commons.digest.HashUtil;
import lucee.commons.io.IOUtil;
import lucee.commons.io.SystemUtil;
import lucee.commons.io.log.Log;
import lucee.commons.io.res.Resource;
import lucee.commons.io.res.util.ResourceUtil;
import lucee.commons.lang.StringUtil;
import lucee.loader.util.Util;
import lucee.runtime.config.Config;
import lucee.runtime.config.ConfigImpl;
import lucee.runtime.config.ConfigWeb;
import lucee.runtime.config.ConfigWebUtil;
import lucee.runtime.config.Constants;
import lucee.runtime.config.DeployHandler;
import lucee.runtime.config.XMLConfigAdmin;
import lucee.runtime.db.ClassDefinition;
import lucee.runtime.engine.ThreadLocalConfig;
import lucee.runtime.engine.ThreadLocalPageContext;
import lucee.runtime.exp.ApplicationException;
import lucee.runtime.exp.DatabaseException;
import lucee.runtime.exp.PageException;
import lucee.runtime.exp.PageRuntimeException;
import lucee.runtime.functions.conversion.DeserializeJSON;
import lucee.runtime.op.Caster;
import lucee.runtime.op.Decision;
import lucee.runtime.osgi.BundleFile;
import lucee.runtime.osgi.BundleInfo;
import lucee.runtime.osgi.OSGiUtil;
import lucee.runtime.osgi.OSGiUtil.BundleDefinition;
import lucee.runtime.type.Collection.Key;
import lucee.runtime.type.KeyImpl;
import lucee.runtime.type.Query;
import lucee.runtime.type.QueryImpl;
import lucee.runtime.type.util.ArrayUtil;
import lucee.runtime.type.util.KeyConstants;
import lucee.runtime.type.util.ListUtil;

import org.osgi.framework.BundleException;
import org.osgi.framework.Version;
import org.w3c.dom.Element;


/**
 * Extension completely handled by the engine and not by the Install/config.xml 
 */
public class RHExtension implements Serializable {
	
	private static final long serialVersionUID = 2904020095330689714L;

	//public static final Key JARS = KeyImpl.init("jars");
	private static final Key BUNDLES = KeyImpl.init("bundles");
	private static final Key TLDS = KeyImpl.init("tlds");
	private static final Key FLDS = KeyImpl.init("flds");
	private static final Key EVENT_GATEWAYS = KeyImpl.init("eventGateways");
	private static final Key TAGS = KeyImpl.init("tags");
	private static final Key FUNCTIONS = KeyConstants._functions;
	private static final Key ARCHIVES = KeyImpl.init("archives");
	private static final Key CONTEXTS = KeyImpl.init("contexts");
	private static final Key WEBCONTEXTS = KeyImpl.init("webcontexts");
	private static final Key COMPONENTS = KeyImpl.init("components");
	private static final Key APPLICATIONS = KeyImpl.init("applications");
	private static final Key CATEGORIES = KeyImpl.init("categories");
	private static final Key PLUGINS = KeyImpl.init("plugins");
	private static final Key START_BUNDLES = KeyImpl.init("startBundles");
	private static final Key TRIAL = KeyImpl.init("trial");
	private static final Key RELEASE_TYPE = KeyImpl.init("releaseType");
    
	private static final String[] EMPTY = new String[0];
	private static final BundleDefinition[] EMPTY_BD = new BundleDefinition[0];

	public static final int RELEASE_TYPE_ALL=0;
	public static final int RELEASE_TYPE_SERVER=1;
	public static final int RELEASE_TYPE_WEB=2;


	private final String id;
	private final int releaseType;
	private final String version;
	private final String name;
	

	private final String description;
	private final boolean trial;
	private final String image;
	private final boolean startBundles;
	private final BundleInfo[] bundles;
	private final String[] jars;
	private final String[] flds;
	private final String[] tlds;
	private final String[] tags;
	private final String[] functions;
	private final String[] archives;
	private final String[] applications;
	private final String[] components;
	private final String[] plugins;
	private final String[] contexts;
	private final String[] webContexts;
	private final String[] categories;
	private final String[] gateways;

	private final List<Map<String, String>> caches;
	private final List<Map<String, String>> cacheHandlers;
	private final List<Map<String, String>> orms;
	private final List<Map<String, String>> monitors;
	private final List<Map<String, String>> searchs;
	private final List<Map<String, String>> resources;
	private final List<Map<String, String>> amfs;
	private final List<Map<String, String>> jdbcs;
	private final List<Map<String, String>> mappings;
	
	private Resource extensionFile;

	private String type;
	private Config config;
	


	public RHExtension(Config config,Element el) throws PageException, IOException, BundleException {
		this(config,toResource(config,el),false);
	}
	
	public RHExtension(Config config, Resource ext, boolean moveIfNecessary) throws PageException, IOException, BundleException {
		// make sure the config is registerd with the thread
		if(ThreadLocalPageContext.getConfig()==null) ThreadLocalConfig.register(config);
		this.config=config;
		// is it a web or server context?
		boolean isWeb=config instanceof ConfigWeb;
		type=isWeb?"web":"server";
		Log logger = ((ConfigImpl)config).getLog("deploy");
		
		// get info necessary for checking
		Info info = ConfigWebUtil.getEngine(config).getInfo();
		
		
		// get the Manifest
		Manifest manifest = null;
		ZipInputStream zis=null;
		String _img=null;
		try {
			zis = new ZipInputStream( IOUtil.toBufferedInputStream(ext.getInputStream()) ) ;	 
			ZipEntry entry;
			String name;
			while ( ( entry = zis.getNextEntry()) != null ) {
				name=entry.getName();
				if(!entry.isDirectory() && name.equalsIgnoreCase("META-INF/MANIFEST.MF")) {
					manifest = toManifest(config,zis,null);
				}
				else if(!entry.isDirectory() && name.equalsIgnoreCase("META-INF/logo.png")) {
					_img = toBase64(zis,null);
				}
				
				zis.closeEntry() ;
			}
		}
		catch(Throwable t){
			throw Caster.toPageException(t);
		}
		finally {
			IOUtil.closeEL(zis);
		}
		if(manifest==null)
			throw new ApplicationException("The Extension ["+ext+"] is invalid,no Manifest file was found at [META-INF/MANIFEST.MF].");
		
		
		
		// read the manifest

		List<Map<String,String>> caches=null;
		List<Map<String,String>> cacheHandlers=null;
		List<Map<String,String>> orms=null;
		List<Map<String,String>> monitors=null;
		List<Map<String,String>> searchs=null;
		List<Map<String,String>> resources=null;
		List<Map<String,String>> amfs=null;
		List<Map<String,String>> jdbcs=null;
		List<Map<String,String>> eventGateways=null;
		List<Map<String,String>> mappings=null;
		
		Attributes attr = manifest.getMainAttributes();
		// version
		version=StringUtil.unwrap(attr.getValue("version"));
		if(StringUtil.isEmpty(version)) {
			throw new ApplicationException("cannot deploy extension ["+ext+"], this Extension has no version information.");
		}
		
		// id
		id=StringUtil.unwrap(attr.getValue("id"));
		if(!Decision.isUUId(id)) {
			throw new ApplicationException("The Extension ["+ext+"] has no valid id defined ("+id+"),id must be a valid UUID.");
		}
		
		// name
		String str=StringUtil.unwrap(attr.getValue("name"));
		if(StringUtil.isEmpty(str,true)) {
			throw new ApplicationException("The Extension ["+ext+"] has no name defined, a name is necesary.");
		}
		name=str.trim();
		

		// release type 
		str=StringUtil.unwrap(attr.getValue("release-type"));
		int rt=RELEASE_TYPE_ALL;
		if(!Util.isEmpty(str)) {
			str=str.trim();
			if("server".equalsIgnoreCase(str)) rt=RELEASE_TYPE_SERVER;
			else if("web".equalsIgnoreCase(str)) rt=RELEASE_TYPE_WEB;
		}
		if((rt==RELEASE_TYPE_SERVER && isWeb) || (rt==RELEASE_TYPE_WEB && !isWeb)) {
			throw new ApplicationException("Cannot install the Extension ["+ext+"] in the "+type+" context, this Extension has the release type ["+toReleaseType(rt, "")+"].");
		}
		releaseType=rt;
		
		
		// description
		description=StringUtil.unwrap(attr.getValue("description"));
		trial=Caster.toBooleanValue(StringUtil.unwrap(attr.getValue("trial")),false);
		
		// image
		if(_img==null)_img=StringUtil.unwrap(attr.getValue("image"));
		image=_img;
		
		// categories
		str=StringUtil.unwrap(attr.getValue("category"));
		if(StringUtil.isEmpty(str,true))str=StringUtil.unwrap(attr.getValue("categories"));
		if(!StringUtil.isEmpty(str,true)) {
			categories=ListUtil.trimItems(ListUtil.listToStringArray(str, ","));
		}
		else categories=null;
		
		// core version
		str=StringUtil.unwrap(attr.getValue("lucee-core-version"));
		//int minCoreVersion=InfoImpl.toIntVersion(str,0);
		Version minCoreVersion = OSGiUtil.toVersion(str, null);
		if(minCoreVersion!=null && Util.isNewerThan(minCoreVersion,info.getVersion())) {
			throw new ApplicationException("The Extension ["+ext+"] cannot be loaded, "+Constants.NAME+" Version must be at least ["+minCoreVersion.toString()+"], version is ["+info.getVersion().toString()+"].");
		}
		
		// loader version
		str=StringUtil.unwrap(attr.getValue("lucee-loader-version"));
		double minLoaderVersion = Caster.toDoubleValue(str,0);
		if(minLoaderVersion>SystemUtil.getLoaderVersion()) {
			throw new ApplicationException("The Extension ["+ext+"] cannot be loaded, "+Constants.NAME+" Loader Version must be at least ["+str+"], update the Lucee.jar first.");
		}
		
		// start bundles
		str = StringUtil.unwrap(attr.getValue("start-bundles"));
		startBundles=Caster.toBooleanValue(str,true);
					

		// amf
		str=StringUtil.unwrap(attr.getValue("amf"));
		if(!StringUtil.isEmpty(str,true)) {
			amfs = toSettings(logger,str);
		}
		
		// resource
		str=StringUtil.unwrap(attr.getValue("resource"));
		if(!StringUtil.isEmpty(str,true)) {
			resources = toSettings(logger,str);
		}

		// search
		str=StringUtil.unwrap(attr.getValue("search"));
		if(!StringUtil.isEmpty(str,true)) {
			searchs = toSettings(logger,str);
		}
		
		// orm
		str=StringUtil.unwrap(attr.getValue("orm"));
		if(!StringUtil.isEmpty(str,true)) {
			orms = toSettings(logger,str);
		}
		
		// monitor
		str=StringUtil.unwrap(attr.getValue("monitor"));
		if(!StringUtil.isEmpty(str,true)) {
			monitors = toSettings(logger,str);
		}
		
		// cache
		str=StringUtil.unwrap(attr.getValue("cache"));
		if(!StringUtil.isEmpty(str,true)) {
			caches = toSettings(logger,str);
		}
		
		// cache-handlers
		str=StringUtil.unwrap(attr.getValue("cache-handler"));
		if(!StringUtil.isEmpty(str,true)) {
			cacheHandlers = toSettings(logger,str);
		}

		// jdbcs
		str=StringUtil.unwrap(attr.getValue("jdbc"));
		if(!StringUtil.isEmpty(str,true)) {
			jdbcs = toSettings(logger,str);
		}

		// event-handler
		str=StringUtil.unwrap(attr.getValue("event-handler"));
		if(!StringUtil.isEmpty(str,true)) {
			eventGateways = toSettings(logger,str);
		}

		// mappings
		str=StringUtil.unwrap(attr.getValue("mapping"));
		if(!StringUtil.isEmpty(str,true)) {
			mappings = toSettings(logger,str);
		}
		
		// no we read the content of the zip
		zis = new ZipInputStream( IOUtil.toBufferedInputStream(ext.getInputStream()) ) ;	 
		ZipEntry entry;
		String path;
		String fileName,sub;
		
		List<BundleInfo> bundles=new ArrayList<BundleInfo>();
		List<String> jars=new ArrayList<String>();
		List<String> flds=new ArrayList<String>();
		List<String> tlds=new ArrayList<String>();
		List<String> tags=new ArrayList<String>();
		List<String> functions=new ArrayList<String>();
		List<String> contexts=new ArrayList<String>();
		List<String> webContexts=new ArrayList<String>();
		List<String> applications=new ArrayList<String>();
		List<String> components=new ArrayList<String>();
		List<String> plugins=new ArrayList<String>();
		List<String> gateways=new ArrayList<String>();
		List<String> archives=new ArrayList<String>();
		try {
			while ( ( entry = zis.getNextEntry()) != null ) {
				path=entry.getName();
				fileName=fileName(entry);
				sub=subFolder(entry);
				// jars
				if(!entry.isDirectory() && 
					(startsWith(path,type,"jars") || startsWith(path,type,"jar") 
					|| startsWith(path,type,"bundles") || startsWith(path,type,"bundle") 
					|| startsWith(path,type,"lib") || startsWith(path,type,"libs")) && StringUtil.endsWithIgnoreCase(path, ".jar")) {
					
					jars.add(fileName);
					BundleInfo bi = BundleInfo.newInstance(zis, false);
					if(bi.isBundle()) bundles.add(bi);
				}
				
				// flds
				if(!entry.isDirectory() && startsWith(path,type,"flds") && (StringUtil.endsWithIgnoreCase(path, ".fld") || StringUtil.endsWithIgnoreCase(path, ".fldx"))) 
					flds.add(fileName);
				
				// tlds
				if(!entry.isDirectory() && startsWith(path,type,"tlds") && (StringUtil.endsWithIgnoreCase(path, ".tld") || StringUtil.endsWithIgnoreCase(path, ".tldx"))) 
					tlds.add(fileName);
				
				// archives
				if(!entry.isDirectory() && (startsWith(path,type,"archives") || startsWith(path,type,"mappings")) && StringUtil.endsWithIgnoreCase(path, ".lar")) 
					archives.add(fileName);
				
				// event-gateway
				if(!entry.isDirectory() && 
						(startsWith(path,type,"event-gateway") || startsWith(path,type,"eventGateway")) && 
						( 
							StringUtil.endsWithIgnoreCase(path, "."+Constants.getCFMLComponentExtension()) || 
							StringUtil.endsWithIgnoreCase(path, "."+Constants.getLuceeComponentExtension())
						)
					)
					gateways.add(sub);

				
				// tags
				if(!entry.isDirectory() && startsWith(path,type,"tags")) 
					tags.add(sub);
				
				// functions
				if(!entry.isDirectory() && startsWith(path,type,"functions")) 
					functions.add(sub);
	
				// context
				if(!entry.isDirectory() && startsWith(path,type,"context") && !StringUtil.startsWith(fileName(entry), '.')) 
					contexts.add(sub);
				
				// web contextS
				if(!entry.isDirectory() && startsWith(path,type,"webcontexts") && !StringUtil.startsWith(fileName(entry), '.')) 
					webContexts.add(sub);
				
				// applications
				if(!entry.isDirectory() && (startsWith(path,type,"applications")) && !StringUtil.startsWith(fileName(entry), '.'))
					applications.add(sub);
				if(!entry.isDirectory() && (startsWith(path,type,"web")) && !StringUtil.startsWith(fileName(entry), '.'))
					applications.add(sub);
				
				// components
				if(!entry.isDirectory() && (startsWith(path,type,"components")) && !StringUtil.startsWith(fileName(entry), '.'))
					components.add(sub);
				
				// plugins
				if(!entry.isDirectory() && (startsWith(path,type,"plugins")) && !StringUtil.startsWith(fileName(entry), '.')) 
					plugins.add(sub);
				
				
				zis.closeEntry() ;
			}
		}
		finally {
			IOUtil.closeEL(zis);
		}
		this.jars=jars.toArray(new String[jars.size()]);
		this.flds=flds.toArray(new String[flds.size()]);
		this.tlds=tlds.toArray(new String[tlds.size()]);
		this.tags=tags.toArray(new String[tags.size()]);
		this.gateways=gateways.toArray(new String[gateways.size()]);
		this.functions=functions.toArray(new String[functions.size()]);
		this.archives=archives.toArray(new String[archives.size()]);
		
		this.contexts=contexts.toArray(new String[contexts.size()]);
		this.webContexts=webContexts.toArray(new String[webContexts.size()]);
		this.applications=applications.toArray(new String[applications.size()]);
		this.components=components.toArray(new String[components.size()]);
		this.plugins=plugins.toArray(new String[plugins.size()]);
		this.bundles=bundles.toArray(new BundleInfo[bundles.size()]);
		this.caches=caches==null?new ArrayList<Map<String, String>>():caches;
		this.cacheHandlers=cacheHandlers==null?new ArrayList<Map<String, String>>():cacheHandlers;
		this.orms=orms==null?new ArrayList<Map<String, String>>():orms;
		this.monitors=monitors==null?new ArrayList<Map<String, String>>():monitors;
		this.searchs=searchs==null?new ArrayList<Map<String, String>>():searchs;
		this.resources=resources==null?new ArrayList<Map<String, String>>():resources;
		this.amfs=amfs==null?new ArrayList<Map<String, String>>():amfs;
		this.jdbcs=jdbcs==null?new ArrayList<Map<String, String>>():jdbcs;
		this.mappings=mappings==null?new ArrayList<Map<String, String>>():mappings;
		
		// copy the file to extension dir if it is not already there
		this.extensionFile=ext;
		if(moveIfNecessary){
			Resource trg;
			Resource trgDir;
			try {
				trg = getExtensionFile(config, ext,id,name,version);
				trgDir = trg.getParentResource();
				trgDir.mkdirs();
				if(!ext.getParentResource().equals(trgDir)) {
					if(trg.exists()) trg.delete();
					ResourceUtil.moveTo(ext, trg,true);
					this.extensionFile=trg;
				}
			}
			catch(Throwable t){
				throw Caster.toPageException(t);
			}
		}
	}
	
	public void deployBundles() throws IOException, BundleException {
		// no we read the content of the zip
		ZipInputStream zis = new ZipInputStream( IOUtil.toBufferedInputStream(extensionFile.getInputStream()) ) ;	 
		ZipEntry entry;
		String path;
		String fileName;
		
		try {
			while ( ( entry = zis.getNextEntry()) != null ) {
				path=entry.getName();
				fileName=fileName(entry);
				// jars
				if(!entry.isDirectory() && 
					(startsWith(path,type,"jars") || startsWith(path,type,"jar") 
					|| startsWith(path,type,"bundles") || startsWith(path,type,"bundle") 
					|| startsWith(path,type,"lib") || startsWith(path,type,"libs")) && StringUtil.endsWithIgnoreCase(path, ".jar")) {
					
					Object obj = XMLConfigAdmin.installBundle(config,zis,fileName,version,false,false);
					// jar is not a bundle, only a regular jar
					if(!(obj instanceof BundleFile)) {
						Resource tmp=(Resource)obj;
						Resource tmpJar=tmp.getParentResource().getRealResource(ListUtil.last(path, "\\/"));
						tmp.moveTo(tmpJar);
						XMLConfigAdmin.updateJar(config, tmpJar, false);
					}
				}
										
				zis.closeEntry() ;
			}
		}
		finally {
			IOUtil.closeEL(zis);
		}
	}

	
	private static Resource toResource(Config config, Element el) throws ApplicationException {
		String fileName = el.getAttribute("file-name");
		if(StringUtil.isEmpty(fileName)) throw new ApplicationException("missing attribute [file-name]");
		Resource res=getExtensionDir(config).getRealResource(fileName);
		if(!res.exists())
			throw new ApplicationException("Extension ["+fileName+"] was not found at ["+res+"]");
		return res;
	}

	private static Resource getExtensionFile(Config config, Resource ext, String id,String name, String version) {
		String fileName=HashUtil.create64BitHashAsString(id+version,Character.MAX_RADIX)+"."+ResourceUtil.getExtension(ext, "lex");
		return getExtensionDir(config).getRealResource(fileName);
	}
	
	private static Resource getExtensionDir(Config config) {
		return config.getConfigDir().getRealResource("extensions/installed");
	}

	public static BundleDefinition[] toBundleDefinitions(String strBundles) {
		if(StringUtil.isEmpty(strBundles,true)) return EMPTY_BD;
		
		String[] arrStrs = toArray(strBundles);
		BundleDefinition[] arrBDs;
		if(!ArrayUtil.isEmpty(arrStrs)) {
			arrBDs = new BundleDefinition[arrStrs.length];
			int index;
			for(int i=0;i<arrStrs.length;i++){
				index=arrStrs[i].indexOf(':');
				if(index==-1) arrBDs[i]=new BundleDefinition(arrStrs[i].trim());
				else {
					try {
						arrBDs[i]=new BundleDefinition(arrStrs[i].substring(0,index).trim(),arrStrs[i].substring(index+1).trim());
					} catch (BundleException e) {
						throw new PageRuntimeException(e);// should not happen
					}
				}
			}
		}
		else arrBDs=EMPTY_BD;
		return arrBDs;
	}

	
	public void populate(Element el) {
		el.setAttribute("file-name", extensionFile.getName());
		el.setAttribute("id", getId());
		el.setAttribute("name", getName());
		el.setAttribute("version", getVersion());
	}
	
	private static String[] toArray(String str) {
		if(StringUtil.isEmpty(str,true)) return new String[0];
		return ListUtil.listToStringArray(str.trim(), ',');
	}
	
	public static Query toQuery(Config config,RHExtension[] children) throws PageException {
		Log log = config.getLog("deploy");
		Query qry = createQuery();
		for(int i=0;i<children.length;i++) {
			try{
				children[i].populate(qry); // ,i+1
			}
			catch(Throwable t){
				log.error("extension", t);
			}
      	}
		return qry;
	}

	public static Query toQuery(Config config,Element[] children) throws PageException {
		Log log = config.getLog("deploy");
		Query qry = createQuery();
		for(int i=0;i<children.length;i++) {
			try{
				new RHExtension(config,children[i]).populate(qry); // ,i+1
			}
			catch(Throwable t){
				log.error("extension", t);
			}
      	}
		return qry;
	}

	private static Query createQuery() throws DatabaseException {
		return new QueryImpl(new Key[]{
      			KeyConstants._id
      			,KeyConstants._version
      			,KeyConstants._name
      			,KeyConstants._description
      			,KeyConstants._image
      			,RELEASE_TYPE
      			,TRIAL
      			,CATEGORIES
      			,START_BUNDLES
      			,BUNDLES
      			,FLDS
      			,TLDS
      			,TAGS
      			,FUNCTIONS
      			,CONTEXTS
      			,WEBCONTEXTS
      			,APPLICATIONS
      			,COMPONENTS
      			,PLUGINS
      			,EVENT_GATEWAYS
      			,ARCHIVES
      	}, 0, "Extensions");
	}

	private void populate(Query qry) throws PageException {
		int row=qry.addRow();
		qry.setAt(KeyConstants._id, row, getId());
  	    qry.setAt(KeyConstants._name, row, name);
  	    qry.setAt(KeyConstants._image, row, getImage());
  	  	qry.setAt(KeyConstants._description, row, description);
  	  	qry.setAt(KeyConstants._version, row, getVersion()==null?null:getVersion().toString());
  	  	qry.setAt(TRIAL, row, isTrial());
  	  	qry.setAt(RELEASE_TYPE, row, toReleaseType(getReleaseType(),"all"));
  	  	//qry.setAt(JARS, row,Caster.toArray(getJars()));
  	  	qry.setAt(FLDS, row, Caster.toArray(getFlds()));
	    qry.setAt(TLDS, row, Caster.toArray(getTlds()));
	    qry.setAt(FUNCTIONS, row, Caster.toArray(getFunctions()));
	    qry.setAt(ARCHIVES, row, Caster.toArray(getArchives()));
  	    qry.setAt(TAGS, row, Caster.toArray(getTags()));
  	    qry.setAt(CONTEXTS, row, Caster.toArray(getContexts()));
  	  	qry.setAt(WEBCONTEXTS, row, Caster.toArray(getWebContexts()));
  	  	qry.setAt(EVENT_GATEWAYS, row, Caster.toArray(getEventGateways()));
	    qry.setAt(CATEGORIES, row, Caster.toArray(getCategories()));
	    qry.setAt(APPLICATIONS, row, Caster.toArray(getApplications()));
	    qry.setAt(COMPONENTS, row, Caster.toArray(getComponents()));
  		qry.setAt(PLUGINS, row, Caster.toArray(getPlugins()));
	    qry.setAt(START_BUNDLES, row, Caster.toBoolean(getStartBundles()));
  	    
  	    BundleInfo[] bfs = getBundles();
  	    Query qryBundles=new QueryImpl(new Key[]{KeyConstants._name,KeyConstants._version}, bfs.length, "bundles");
  	    for(int i=0;i<bfs.length;i++){
  	    	qryBundles.setAt(KeyConstants._name, i+1, bfs[i].getSymbolicName());
  	    	if(bfs[i].getVersion()!=null)
  	    		qryBundles.setAt(KeyConstants._version, i+1, bfs[i].getVersionAsString());
  	    }
  	    qry.setAt(BUNDLES, row,qryBundles);
	}
	



	public String getId() {
		return id;
	}

	public String getImage() {
		return image;
	}
	public String getVersion() {
		return version;
	}


	public boolean getStartBundles() {
		return startBundles;
	}




	
	/*private static void moveToFailedFolder(Resource deployDirectory,Resource res) {
		Resource dir = deployDirectory.getRealResource("failed-to-deploy");
		Resource dst = dir.getRealResource(res.getName());
		dir.mkdirs();
		
		try {
			if(dst.exists()) dst.remove(true);
			ResourceUtil.moveTo(res, dst,true);
		}
		catch (Throwable t) {}
		
		// TODO Auto-generated method stub
		
	}*/
	
	private static Manifest toManifest(Config config,InputStream is, Manifest defaultValue) {
		try {
			Charset cs = config.getResourceCharset();
			String str = IOUtil.toString(is,cs);
			if(StringUtil.isEmpty(str,true)) return defaultValue;
			str=str.trim()+"\n";
			return new Manifest(new ByteArrayInputStream(str.getBytes(cs)));
		}
		catch (Throwable t) {
			return defaultValue;
		}
	}
	
	private static String toBase64(InputStream is, String defaultValue) {
		try {
			byte[] bytes = IOUtil.toBytes(is);
			if(ArrayUtil.isEmpty(bytes)) return defaultValue;
			return Caster.toB64(bytes,defaultValue);
		}
		catch (Throwable t) {
			return defaultValue;
		}
	}
	
	public static ClassDefinition<?> toClassDefinition(Config config, Map<String, String> map) {
		String _class=map.get("class");
		
		String _name=map.get("bundle-name");
		if(StringUtil.isEmpty(_name)) _name=map.get("bundleName");
		if(StringUtil.isEmpty(_name)) _name=map.get("bundlename");
		if(StringUtil.isEmpty(_name)) _name=map.get("name");
		
		String _version=map.get("bundle-version");
		if(StringUtil.isEmpty(_version)) _version=map.get("bundleVersion");
		if(StringUtil.isEmpty(_version)) _version=map.get("bundleversion");
		if(StringUtil.isEmpty(_version)) _version=map.get("version");
		
		return new lucee.transformer.library.ClassDefinitionImpl(
				_class
				,_name
				,_version
				,config.getIdentification());
		
	}

	private static List<Map<String,String>> toSettings(Log log, String str) {
		try {
			Object res = DeserializeJSON.call(null, str);
			// only a single row
			if(!Decision.isArray(res) && Decision.isStruct(res)) {
				List<Map<String,String>> list = new ArrayList<>();
				_toSetting(list, Caster.toMap(res));
				return list;
			}
			// multiple rows
			if(Decision.isArray(res)) {
				List tmpList=Caster.toList(res);
				Iterator it = tmpList.iterator();
				List<Map<String,String>> list=new ArrayList<>();
				while(it.hasNext()) {
					_toSetting(list,Caster.toMap(it.next()));
				}
				return list;
			}
			
		} 
		catch (Throwable t) {
			log.error("Extension Installation", t);
		}
		
		return null;
	}
	private static void _toSetting(List<Map<String, String>> list, Map src) throws PageException {
		Entry e;
		Iterator<Entry> it = src.entrySet().iterator();
		Map<String,String> map=new HashMap<String,String>();
		while(it.hasNext()){
			e = it.next();
			map.put(Caster.toString(e.getKey()), Caster.toString(e.getValue()));
		}
		list.add(map);
	}

	private static boolean startsWith(String path,String type, String name) {
		return StringUtil.startsWithIgnoreCase(path, name+"/") || StringUtil.startsWithIgnoreCase(path, type+"/"+name+"/");
	}

	private static String fileName(ZipEntry entry) {
		String name = entry.getName();
		int index=name.lastIndexOf('/');
		if(index==-1) return name;
		return name.substring(index+1);
	}
	private static String subFolder(ZipEntry entry) {
		String name = entry.getName();
		int index=name.indexOf('/');
		if(index==-1) return name;
		return name.substring(index+1);
	}
	
	private static BundleDefinition toBundleDefinition(InputStream is, String name,String extensionVersion,boolean closeStream) throws IOException, BundleException, ApplicationException {
		Resource tmp=SystemUtil.getTempDirectory().getRealResource(name);
		try{
			IOUtil.copy(is, tmp,closeStream);
			BundleFile bf = new BundleFile(tmp);
			if(bf.isBundle()) throw new ApplicationException("Jar ["+name+"] is not a valid OSGi Bundle");
			return new BundleDefinition(bf.getSymbolicName(), bf.getVersion());
		}
		finally {
			tmp.delete();
		}
	}
	
	
	public String getName() {
		return name;
	}

	public boolean isTrial() {
		return trial;
	}

	public String getDescription() {
		return description;
	}
	public int getReleaseType() {
		return releaseType;
	}

	public BundleInfo[] getBundles() {
		return bundles;
	}

	public String[] getFlds() {
		return flds==null?EMPTY:flds;
	}
	
	public String[] getJars() {
		return jars==null?EMPTY:jars;
	}

	public String[] getTlds() {
		return tlds==null?EMPTY:tlds;
	}

	public String[] getFunctions() {
		return functions==null?EMPTY:functions;
	}
	
	public String[] getArchives() {
		return archives==null?EMPTY:archives;
	}

	public String[] getTags() {
		return tags==null?EMPTY:tags;
	}
	

	public String[] getEventGateways() {
		return gateways==null?EMPTY:gateways;
	}

	public String[] getApplications() {
		return applications==null?EMPTY:applications;
	}

	public String[] getComponents() {
		return components==null?EMPTY:components;
	}

	public String[] getPlugins() {
		return plugins==null?EMPTY:plugins;
	}

	public String[] getContexts() {
		return contexts==null?EMPTY:contexts;
	}

	public String[] getWebContexts() {
		return webContexts==null?EMPTY:webContexts;
	}

	public String[] getCategories() {
		return categories==null?EMPTY:categories;
	}

	public List<Map<String, String>> getCaches() {
		return caches;
	}
	
	public List<Map<String, String>> getCacheHandlers() {
		return cacheHandlers;
	}

	public List<Map<String, String>> getOrms() {
		return orms;
	}
	public List<Map<String, String>> getMonitors() {
		return monitors;
	}

	public List<Map<String, String>> getSearchs() {
		return searchs;
	}
	public List<Map<String, String>> getResources() {
		return resources;
	}
	public List<Map<String, String>> getAMFs() {
		return amfs;
	}

	public List<Map<String, String>> getJdbcs() {
		return jdbcs;
	}
	public List<Map<String, String>> getMappings() {
		return mappings;
	}

	public Resource getExtensionFile() {
		if(!extensionFile.exists()) {
			Config c = ThreadLocalPageContext.getConfig();
			if(c!=null) {
				Resource res = DeployHandler.getExtension(c,new ExtensionDefintion( id, version), null);
				if(res!=null && res.exists()) {
					try {
						IOUtil.copy(res, extensionFile);
					}
					catch (IOException e) {
						res.delete();
					}
				}
			}
		}
		return extensionFile;
	}
	
	@Override
	public boolean equals(Object objOther) {
		if(objOther == this) return true;
		
		if(objOther instanceof RHExtension) {
			RHExtension other=(RHExtension) objOther;
	
			if(!id.equals(other.id)) return false;
			if(!name.equals(other.name)) return false;
			if(!version.equals(other.version)) return false;
			if(trial!=other.trial) return false;
			return true;
		}
		if(objOther instanceof ExtensionDefintion) {
			ExtensionDefintion ed=(ExtensionDefintion) objOther;
			if(!ed.getId().equalsIgnoreCase(getId())) return false;
			if(ed.getVersion()==null || getVersion()==null) return true;
			return ed.getVersion().equalsIgnoreCase(getVersion());
		}
		return false;
	}
	
	

	public static String toReleaseType(int releaseType, String defaultValue) {
		if(releaseType==RELEASE_TYPE_WEB) return "web";
		if(releaseType==RELEASE_TYPE_SERVER) return "server";
		if(releaseType==RELEASE_TYPE_ALL) return "all";
		return defaultValue;
	}
	public static int toReleaseType(String releaseType, int defaultValue) {
		if("web".equalsIgnoreCase(releaseType)) return RELEASE_TYPE_WEB;
		if("server".equalsIgnoreCase(releaseType)) return RELEASE_TYPE_SERVER;
		if("all".equalsIgnoreCase(releaseType)) return RELEASE_TYPE_ALL;
		if("both".equalsIgnoreCase(releaseType)) return RELEASE_TYPE_ALL;
		return defaultValue;
	}

	public static List<ExtensionDefintion> toExtensionDefinitions(String str) {
		// first we split the list
		List<ExtensionDefintion> rtn=new ArrayList<ExtensionDefintion>();
		if(StringUtil.isEmpty(str)) return rtn;
		
		String[] arr = ListUtil.trimItems(ListUtil.listToStringArray(str, ','));
		if(ArrayUtil.isEmpty(arr)) return rtn;
		
		String[] arrr;
		int index;
		ExtensionDefintion ed;
		String s;
		for(int i=0;i<arr.length;i++){
			s=arr[i];
			arrr = ListUtil.trimItems(ListUtil.listToStringArray(s, ';'));
			ed=new ExtensionDefintion();
			for(String ss:arrr){
				index=ss.indexOf('=');
				if(index!=-1) {
					ed.setParam(ss.substring(0,index).trim(),ss.substring(index+1).trim());
				}
				else ed.setId(ss);
			}
			rtn.add(ed);
		}
		return rtn;
	}
}
