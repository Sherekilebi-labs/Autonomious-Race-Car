package org.maxbot.core;
import java.util.*;
/** Tensor/decoder contract; no family name is treated as an output-layout guess. */
public final class ModelManifest {
  public static final class Evaluation {public boolean realConeValidated;public String datasetId,reportSha256;public int frames;public Double coneRecall,falsePositivesPerFrame,centerErrorP95M;}
  public Evaluation evaluation;
  public boolean liveValidated(){return evaluation!=null&&evaluation.realConeValidated&&evaluation.frames>0&&evaluation.datasetId!=null&&!evaluation.datasetId.isEmpty()&&evaluation.reportSha256!=null&&evaluation.reportSha256.matches("[a-f0-9]{64}")&&evaluation.coneRecall!=null&&Double.isFinite(evaluation.coneRecall)&&evaluation.coneRecall>=0&&evaluation.coneRecall<=1&&evaluation.falsePositivesPerFrame!=null&&Double.isFinite(evaluation.falsePositivesPerFrame)&&evaluation.falsePositivesPerFrame>=0&&evaluation.centerErrorP95M!=null&&Double.isFinite(evaluation.centerErrorP95M)&&evaluation.centerErrorP95M>=0;}
  public int schemaVersion;
  public String sha256,family,exporterVersion,decoder,inputType,outputType,normalization,coordinates,orientation;
  public int[] inputShape,outputShape;
  public String[] classes;
  public boolean nmsRequired;
  public double inputScale,outputScale;
  public int inputZero,outputZero;
  public List<String> validate(){
    List<String> m=new ArrayList<>();
    if(schemaVersion!=1||sha256==null||!sha256.matches("[a-f0-9]{64}")||exporterVersion==null||classes==null||!Arrays.equals(classes,new String[]{"cone"}))m.add("Version/hash/exporter/classes required");
    if(!Arrays.equals(inputShape,new int[]{1,320,320,3})||!"zero_to_one".equals(normalization)||!"sensor_cpu_unrotated_letterbox".equals(orientation))m.add("Unsupported preprocessing");
    if("yolov5_xywh_objectness".equals(decoder)){
      if(!"yolov5n".equals(family)||!Arrays.equals(outputShape,new int[]{1,6300,6})||!nmsRequired||!"normalized".equals(coordinates))m.add("Invalid YOLOv5 contract");
    }else if("end_to_end_xyxy_score_class".equals(decoder)){
      if(!"yolo26n".equals(family)||outputShape==null||outputShape.length!=3||outputShape[0]!=1||outputShape[1]<1||outputShape[1]>1000||outputShape[2]!=6||nmsRequired||!"input_pixels".equals(coordinates))m.add("Invalid explicitly exported YOLO26 contract");
    }else m.add("Unsupported decoder; inspect actual exporter tensors");
    for(String type:new String[]{inputType,outputType})if(!List.of("FLOAT32","INT8","UINT8").contains(type==null?"":type))m.add("Unsupported dtype");
    if(!"FLOAT32".equals(inputType)&&(!Double.isFinite(inputScale)||inputScale<=0))m.add("Input quantization missing");
    if(!"FLOAT32".equals(outputType)&&(!Double.isFinite(outputScale)||outputScale<=0))m.add("Output quantization missing");
    return m;
  }
}
