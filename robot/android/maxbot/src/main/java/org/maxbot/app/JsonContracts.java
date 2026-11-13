package org.maxbot.app;
import android.content.Context;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
/** Runtime subset used by the checked-in closed schemas; unsupported schema constructs fail closed. */
final class JsonContracts {
  static void validate(Context context,String schemaName,String raw)throws Exception{
    Object value=new JSONTokener(raw).nextValue();String schema;
    try(InputStream in=context.getAssets().open("maxbot/contracts/"+schemaName+".v1.schema.json")){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);schema=out.toString("UTF-8");}
    check(new JSONObject(schema),value,"$");
  }
  private static boolean same(Object a,Object b){if(a instanceof Number&&b instanceof Number)return ((Number)a).doubleValue()==((Number)b).doubleValue();return a.toString().equals(b.toString());}
  private static void check(JSONObject s,Object v,String path)throws Exception{
    if(s.has("const")&&!same(s.get("const"),v))throw new IllegalArgumentException(path+": constant mismatch");
    if(s.has("enum")){boolean found=false;JSONArray a=s.getJSONArray("enum");for(int i=0;i<a.length();i++)found|=same(a.get(i),v);if(!found)throw new IllegalArgumentException(path+": unsupported value");}
    Object types=s.opt("type");if(types!=null){boolean ok=false;if(types instanceof JSONArray){JSONArray a=(JSONArray)types;for(int i=0;i<a.length();i++)ok|=type(a.getString(i),v);}else ok=type(types.toString(),v);if(!ok)throw new IllegalArgumentException(path+": invalid type");}
    if(v instanceof Number){double n=((Number)v).doubleValue();if(!Double.isFinite(n)||s.has("minimum")&&n<s.getDouble("minimum")||s.has("maximum")&&n>s.getDouble("maximum")||s.has("exclusiveMinimum")&&n<=s.getDouble("exclusiveMinimum"))throw new IllegalArgumentException(path+": numeric bounds");}
    if(v instanceof String){String t=(String)v;if(t.length()>s.optInt("maxLength",65536)||t.length()<s.optInt("minLength",0)||s.has("pattern")&&!t.matches(s.getString("pattern")))throw new IllegalArgumentException(path+": string bounds");}
    if(v instanceof JSONArray){JSONArray a=(JSONArray)v;if(a.length()>s.optInt("maxItems",8192)||a.length()<s.optInt("minItems",0))throw new IllegalArgumentException(path+": array bounds");if(s.has("items"))for(int i=0;i<a.length();i++)check(s.getJSONObject("items"),a.get(i),path+"["+i+"]");}
    if(v instanceof JSONObject){JSONObject o=(JSONObject)v,props=s.optJSONObject("properties");JSONArray req=s.optJSONArray("required");if(req!=null)for(int i=0;i<req.length();i++)if(!o.has(req.getString(i)))throw new IllegalArgumentException(path+": missing "+req.getString(i));java.util.Iterator<String> keys=o.keys();while(keys.hasNext()){String k=keys.next();if(props!=null&&props.has(k))check(props.getJSONObject(k),o.get(k),path+"."+k);else if(!s.optBoolean("additionalProperties",true))throw new IllegalArgumentException(path+": unknown field "+k);}}
  }
  private static boolean type(String t,Object v){switch(t){case "null":return v==JSONObject.NULL;case "object":return v instanceof JSONObject;case "array":return v instanceof JSONArray;case "boolean":return v instanceof Boolean;case "string":return v instanceof String;case "number":return v instanceof Number;case "integer":return v instanceof Number&&Double.isFinite(((Number)v).doubleValue())&&((Number)v).doubleValue()==Math.rint(((Number)v).doubleValue());default:return false;}}
}
