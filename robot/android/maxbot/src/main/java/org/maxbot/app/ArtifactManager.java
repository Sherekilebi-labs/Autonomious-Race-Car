package org.maxbot.app;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;
/** Bounded staged upload, durable atomic set pointer, prepare/warm before activation, rollback. */
final class ArtifactManager {
  interface Activator {Runnable prepare(File directory,JSONObject manifest)throws Exception;}
  private final File root;private final Activator activator;
  private File staging;private JSONObject manifest;private String transaction;private long total;
  ArtifactManager(File files,Activator activator)throws IOException{root=new File(files,"artifacts");if(!root.exists()&&!root.mkdirs())throw new IOException("Artifact storage unavailable");this.activator=activator;}
  static String hashBytes(byte[] bytes)throws Exception{byte[] digest=MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder out=new StringBuilder();for(byte value:digest)out.append(String.format(Locale.ROOT,"%02x",value&255));return out.toString();}
  static String hash(File file)throws Exception{MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)digest.update(b,0,n);}StringBuilder out=new StringBuilder();for(byte v:digest.digest())out.append(String.format(Locale.ROOT,"%02x",v&255));return out.toString();}
  synchronized String receive(JSONObject m,boolean stopped)throws Exception{
    if(!stopped)throw new IllegalStateException("Uploads/activation pause until stopped and disarmed");
    String action=m.getString("operation");String id=m.getString("transaction");
    if(!id.matches("[a-f0-9]{32}"))throw new IllegalArgumentException("Invalid transaction");
    if("begin".equals(action)){
      JSONObject next=m.getJSONObject("manifest");JSONArray entries=next.getJSONArray("files");
      if(next.getInt("schemaVersion")!=1||next.getLong("revision")<1||entries.length()<1||entries.length()>8)throw new IllegalArgumentException("Unsupported artifact set");
      long bytes=0;Set<String> names=new HashSet<>(),kinds=new HashSet<>();
      for(int i=0;i<entries.length();i++){JSONObject e=entries.getJSONObject(i);String name=e.getString("name");long size=e.getLong("size");if(!name.matches("[a-z0-9_.-]{1,64}")||name.startsWith(".")||!names.add(name)||!kinds.add(e.getString("kind"))||size<1||size>32*1024*1024||!e.getString("sha256").matches("[a-f0-9]{64}")||!List.of("model","manifest","dynamics","reference","policy","settings","geometry").contains(e.getString("kind")))throw new IllegalArgumentException("Invalid entry");bytes+=size;}
      if(bytes>40*1024*1024)throw new IllegalArgumentException("Artifact set too large");
      staging=new File(root,"stage-"+id);if(!staging.mkdir())throw new IllegalStateException("Transaction already exists");transaction=id;manifest=next;total=0;return "staged";
    }
    if(!id.equals(transaction)||staging==null)throw new IllegalStateException("Unknown/expired staging transaction");
    if("chunk".equals(action)){
      String name=m.getString("name");JSONObject entry=entry(name);File file=new File(staging,name);long offset=m.getLong("offset");
      if(offset!=file.length())throw new IllegalArgumentException("Chunk offset mismatch");
      String encoded=m.getString("data");if(encoded.length()>44000)throw new IllegalArgumentException("Chunk too large");byte[] data=android.util.Base64.decode(encoded,android.util.Base64.NO_WRAP);
      if(file.length()+data.length>entry.getLong("size")||total+data.length>40*1024*1024)throw new IllegalArgumentException("Upload bound");
      try(FileOutputStream out=new FileOutputStream(file,true)){out.write(data);out.getFD().sync();}total+=data.length;return "chunk staged";
    }
    if(!"activate".equals(action))throw new IllegalArgumentException("Unknown artifact operation");
    JSONArray entries=manifest.getJSONArray("files");
    for(int i=0;i<entries.length();i++){JSONObject e=entries.getJSONObject(i);File file=new File(staging,e.getString("name"));if(file.length()!=e.getLong("size")||!hash(file).equals(e.getString("sha256")))throw new IllegalArgumentException("Partial/corrupt artifact: "+e.getString("name"));}
    File pointer=new File(root,"active.json");String previous=pointer.exists()?new String(Files.readAllBytes(pointer.toPath()),java.nio.charset.StandardCharsets.UTF_8):null;
    if(previous!=null&&manifest.getLong("revision")<=new JSONObject(previous).getLong("revision"))throw new IllegalArgumentException("Artifact revision must increase");
    Runnable apply=activator.prepare(staging,manifest); // constructs/warms before persisted active-set acknowledgement
    JSONObject next=new JSONObject(manifest.toString()).put("directory",staging.getName());writeAtomic(pointer,next.toString());
    try{apply.run();}catch(RuntimeException e){if(previous==null)Files.deleteIfExists(pointer.toPath());else writeAtomic(pointer,previous);throw e;}
    transaction=null;manifest=null;staging=null;return "applied; disarmed";
  }
  private JSONObject entry(String name)throws Exception{JSONArray entries=manifest.getJSONArray("files");for(int i=0;i<entries.length();i++)if(entries.getJSONObject(i).getString("name").equals(name))return entries.getJSONObject(i);throw new IllegalArgumentException("Unknown artifact name");}
  void restore()throws Exception{File active=new File(root,"active.json");if(active.exists()){JSONObject m=new JSONObject(new String(Files.readAllBytes(active.toPath()),java.nio.charset.StandardCharsets.UTF_8));File dir=new File(root,m.getString("directory"));if(!dir.getCanonicalPath().startsWith(root.getCanonicalPath()+File.separator))throw new SecurityException("Artifact path");JSONArray entries=m.getJSONArray("files");for(int i=0;i<entries.length();i++){JSONObject e=entries.getJSONObject(i);if(!hash(new File(dir,e.getString("name"))).equals(e.getString("sha256")))throw new IOException("Active artifact corrupted");}m.put("_restoring",true);activator.prepare(dir,m).run();}}
  private static void writeAtomic(File file,String text)throws IOException{File temp=new File(file.getPath()+".tmp");try(FileOutputStream out=new FileOutputStream(temp)){out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));out.getFD().sync();}Files.move(temp.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
}
