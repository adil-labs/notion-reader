package com.example.discoveryfeasibility;

import android.app.Activity;
import android.os.Bundle;
import android.os.Debug;
import android.util.Log;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import ai.onnxruntime.*;

/** Isolated no-network probe. Does not access the reader or real user documents. */
public class ProbeActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        new Thread(() -> {
            try { probe(); } catch (Throwable failure) { Log.e("DiscoveryProbe", "FAIL", failure); }
        }).start();
    }
    private void probe() throws Exception {
        File model = new File(getFilesDir(), "model.onnx");
        try (var input = getAssets().open("model_qint8_arm64.onnx")) { Files.copy(input, model.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
        JSONObject reference;
        try (var input = getAssets().open("reference.json"); var output = new java.io.ByteArrayOutputStream()) { byte[] buffer = new byte[8192]; int length; while ((length=input.read(buffer)) != -1) output.write(buffer,0,length); reference = new JSONObject(new String(output.toByteArray(), java.nio.charset.StandardCharsets.UTF_8)); }
        long started = System.nanoTime();
        OrtEnvironment environment = OrtEnvironment.getEnvironment();
        environment.setTelemetry(false);
        try (var options = new OrtSession.SessionOptions(); var session = environment.createSession(model.getPath(), options)) {
            double coldMs = (System.nanoTime() - started) / 1e6;
            JSONArray probes = reference.getJSONArray("probes");
            double maximumError = 0;
            double minimumCosine = 1;
            double[] timings = new double[probes.length()];
            double[][] embeddings = new double[probes.length()][];
            for (int p = 0; p < probes.length(); p++) {
                long queryStarted = System.nanoTime();
                JSONObject item = probes.getJSONObject(p);
                Map<String, OnnxTensor> inputs = new HashMap<>();
                try {
                    for (String name : new String[]{"input_ids", "attention_mask", "token_type_ids"}) {
                        JSONArray values = item.getJSONArray(name);
                        long[] data = new long[values.length()];
                        for (int i=0; i<data.length; i++) data[i] = values.getLong(i);
                        inputs.put(name, OnnxTensor.createTensor(environment, LongBuffer.wrap(data), new long[]{1, data.length}));
                    }
                    try (var result = session.run(inputs)) {
                        float[][][] hidden = (float[][][]) result.get(0).getValue();
                        double[] pooled = new double[384];
                        JSONArray mask = item.getJSONArray("attention_mask");
                        int count = 0;
                        for (int i=0;i<hidden[0].length;i++) if (mask.getInt(i) != 0) { count++; for(int j=0;j<384;j++) pooled[j] += hidden[0][i][j]; }
                        double squared=0;
                        for(int j=0;j<384;j++) { pooled[j]/=count; squared+=pooled[j]*pooled[j]; }
                        JSONArray expected=item.getJSONArray("embedding");
                        double cosine = 0;
                        for(int j=0;j<384;j++) { maximumError=Math.max(maximumError, Math.abs(pooled[j]/Math.sqrt(squared)-expected.getDouble(j))); cosine += pooled[j]/Math.sqrt(squared)*expected.getDouble(j); }
                        for(int j=0;j<384;j++) pooled[j] /= Math.sqrt(squared);
                        embeddings[p] = pooled;
                        minimumCosine = Math.min(minimumCosine, cosine);
                    }
                } finally { for (OnnxTensor tensor : inputs.values()) tensor.close(); }
                timings[p] = (System.nanoTime() - queryStarted) / 1e6;
            }
            java.util.Arrays.sort(timings);
            JSONObject report = new JSONObject();
            report.put("sdk", android.os.Build.VERSION.SDK_INT); report.put("abi", android.os.Build.SUPPORTED_ABIS[0]);
            report.put("coldSessionMs",coldMs); report.put("maximumAbsoluteHostParityError",maximumError);
            report.put("minimumHostParityCosine",minimumCosine);
            report.put("queryP50Ms",timings[timings.length/2]); report.put("queryP95Ms",timings[timings.length-1]);
            report.put("querySamples",timings.length);
            JSONArray documents = reference.getJSONArray("documents"), queries = reference.getJSONArray("queries");
            int relevantSelected=0, selected=0, recalled=0, relevantTotal=0;
            double ndcgTotal=0; int heldOut=0;
            JSONArray rankings=new JSONArray();
            for(int q=0;q<queries.length();q++) {
                JSONObject query=queries.getJSONObject(q);
                double[] vector=embeddings[5+documents.length()+q];
                double[] scores=new double[documents.length()];
                Integer[] order=new Integer[scores.length];
                for(int d=0;d<scores.length;d++) { order[d]=d; for(int j=0;j<384;j++) scores[d]+=vector[j]*embeddings[5+d][j]; }
                java.util.Arrays.sort(order,(a,b)->Double.compare(scores[b],scores[a]));
                JSONArray ranked = new JSONArray();
                for(int d:order) ranked.put(new JSONObject().put("id",documents.getJSONObject(d).getString("id")).put("score",scores[d]));
                rankings.put(new JSONObject().put("text",query.getString("text")).put("ranking",ranked));
                if(!query.getString("split").equals("held-out")) continue;
                heldOut++; JSONArray relevant=query.getJSONArray("relevant"); relevantTotal+=relevant.length();
                java.util.Set<String> ids=new java.util.HashSet<>(); for(int r=0;r<relevant.length();r++) ids.add(relevant.getString(r));
                double dcg=0,idcg=0;
                for(int r=0;r<Math.min(5,relevant.length());r++) idcg+=1/(Math.log(r+2)/Math.log(2));
                for(int r=0;r<Math.min(5,order.length);r++) {
                    boolean hit=ids.contains(documents.getJSONObject(order[r]).getString("id"));
                    if(hit) {recalled++;dcg+=1/(Math.log(r+2)/Math.log(2));}
                    if(scores[order[r]]>=reference.getDouble("threshold")) {selected++;if(hit) relevantSelected++;}
                }
                ndcgTotal+=dcg/idcg;
            }
            report.put("heldOutRecallAt5",(double)recalled/relevantTotal);
            report.put("heldOutPrecision",selected==0?0:(double)relevantSelected/selected);
            report.put("heldOutNdcgAt5",ndcgTotal/heldOut);
            report.put("nativeHeapBytes",Debug.getNativeHeapAllocatedSize());
            Debug.MemoryInfo memory = new Debug.MemoryInfo(); Debug.getMemoryInfo(memory); report.put("totalPssKiB",memory.getTotalPss());
            report.put("initialComponentTolerancePassed", maximumError < 0.01);
            report.put("semanticGatePassed",(double)recalled/relevantTotal>=.8 && selected>0 && (double)relevantSelected/selected>=.8);
            try(var output=openFileOutput("report.json",MODE_PRIVATE)) {output.write(report.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
            Log.i("DiscoveryProbe", report.toString());
        }
    }
}
