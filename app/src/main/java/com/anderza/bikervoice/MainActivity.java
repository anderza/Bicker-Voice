package com.anderza.bikervoice;

import android.Manifest;
import android.app.Activity;
import android.os.Bundle;
import android.os.SystemClock;
import android.content.pm.PackageManager;
import android.media.AudioRecord;
import android.media.AudioFormat;
import android.media.MediaRecorder;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.webkit.*;
import org.json.JSONObject;
import org.json.JSONArray;
import android.speech.tts.Voice;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;

public class MainActivity extends Activity {
 private WebView web;
 private TextToSpeech tts;
 private boolean ttsReady=false, destroyed=false, waitingPermission=false, wantMic=false;
 private int micSession=0, speechSession=0;
 private MicCapture capture;
 private void js(String code){ if(!destroyed && web!=null) web.evaluateJavascript(code,null); }
 private void micError(int session,String message){ if(session==micSession)js("nativeMicError("+session+","+JSONObject.quote(message)+")"); }
 @Override public void onCreate(Bundle b){
  super.onCreate(b);
  web=new WebView(this);setContentView(web);
  web.getSettings().setJavaScriptEnabled(true);
  web.getSettings().setDomStorageEnabled(true);
  web.getSettings().setMediaPlaybackRequiresUserGesture(false);
  web.addJavascriptInterface(new VoiceBridge(),"AndroidVoice");
  web.setWebViewClient(new WebViewClient());
  tts=new TextToSpeech(this,status->runOnUiThread(()->{
   if(destroyed||tts==null)return;
   ttsReady=status==TextToSpeech.SUCCESS;
   if(ttsReady){tts.setLanguage(new Locale("ru","RU"));restoreVoice();} sendVoices();
  }));
  web.loadUrl("file:///android_asset/index.html");
 }
 private void restoreVoice(){
  String saved=getPreferences(MODE_PRIVATE).getString("voice","");
  if(tts.getVoices()!=null)for(Voice v:tts.getVoices())if(v.getName().equals(saved)&&v.getLocale().getLanguage().equals("ru")){tts.setVoice(v);break;}
 }
 private void sendVoices(){
  JSONArray list=new JSONArray();
  if(ttsReady&&tts!=null&&tts.getVoices()!=null){
   ArrayList<Voice> voices=new ArrayList<>(tts.getVoices());
   voices.sort(Comparator.comparing(Voice::isNetworkConnectionRequired).thenComparing(Voice::getName));
   for(Voice v:voices){if(!v.getLocale().getLanguage().equals("ru"))continue;try{
    JSONObject o=new JSONObject();o.put("id",v.getName());o.put("network",v.isNetworkConnectionRequired());list.put(o);
   }catch(org.json.JSONException ignored){}}
  }
  String selected=ttsReady&&tts!=null&&tts.getVoice()!=null?tts.getVoice().getName():"";
  js("nativeVoices("+list.toString()+","+JSONObject.quote(selected)+")");
 }
 public class VoiceBridge {
  @JavascriptInterface public void listVoices(){runOnUiThread(()->sendVoices());}
  @JavascriptInterface public void selectVoice(String id){runOnUiThread(()->{
   if(!ttsReady||tts==null||tts.getVoices()==null)return;
   for(Voice v:tts.getVoices())if(v.getName().equals(id)&&v.getLocale().getLanguage().equals("ru")){
    if(tts.setVoice(v)==TextToSpeech.SUCCESS){getPreferences(MODE_PRIVATE).edit().putString("voice",id).apply();sendVoices();}
    else js("nativeSpeechError('Не удалось выбрать голос')");
    return;
   }
  });}

  @JavascriptInterface public void startMic(int session){runOnUiThread(()->{
   if(destroyed)return;
   stopSpeech();stopCapture();micSession=session;wantMic=true;
   if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)startCapture();
   else if(!waitingPermission){waitingPermission=true;requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},7);}
  });}
  @JavascriptInterface public void stopMic(){runOnUiThread(()->{wantMic=false;stopCapture();});}
  @JavascriptInterface public void speak(String text){runOnUiThread(()->{
   wantMic=false;stopCapture();stopSpeech();
   if(!ttsReady||tts==null){js("nativeSpeechError('Синтез речи недоступен')");return;}
   final int session=++speechSession;
   tts.setOnUtteranceProgressListener(new UtteranceProgressListener(){
    @Override public void onStart(String id){if(id.equals("biker-"+session))runOnUiThread(()->{if(session==speechSession)js("nativeSpeechStart()");});}
    @Override public void onRangeStart(String id,int start,int end,int frame){if(id.equals("biker-"+session))runOnUiThread(()->{if(session==speechSession)js("nativeSpeechRange("+start+")");});}
    @Override public void onDone(String id){if(id.equals("biker-"+session))runOnUiThread(()->{if(session==speechSession)js("nativeSpeechEnd()");});}
    @Override public void onError(String id){if(id.equals("biker-"+session))runOnUiThread(()->{if(session==speechSession)js("nativeSpeechError('Ошибка синтеза речи')");});}
   });
   if(tts.speak(text,TextToSpeech.QUEUE_FLUSH,null,"biker-"+session)==TextToSpeech.ERROR)js("nativeSpeechError('Ошибка синтеза речи')");
  });}
  @JavascriptInterface public void stop(){runOnUiThread(()->{wantMic=false;stopCapture();stopSpeech();});}
 }
 private void stopSpeech(){speechSession++;if(tts!=null)tts.stop();}
 private void stopCapture(){MicCapture c=capture;capture=null;if(c!=null)c.close();}
 private void startCapture(){
  if(!wantMic||destroyed)return;
  stopCapture();
  MicCapture c=new MicCapture(micSession);
  capture=c;
  new Thread(c,"BikerVoice-Mic").start();
 }
 private final class MicCapture implements Runnable {
  final int session;
  volatile boolean running=true;
  private AudioRecord recorder;
  MicCapture(int session){this.session=session;}
  synchronized void close(){running=false;if(recorder!=null){try{recorder.stop();}catch(IllegalStateException ignored){}}}
  synchronized boolean open(int source,int rate,int size){
   if(!running)return false;
   AudioRecord candidate=null;
   try{
    candidate=new AudioRecord(source,rate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,size);
    if(candidate.getState()!=AudioRecord.STATE_INITIALIZED){candidate.release();return false;}
    candidate.startRecording();
    if(candidate.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING){candidate.release();return false;}
    recorder=candidate;return true;
   }catch(RuntimeException e){if(candidate!=null)candidate.release();return false;}
  }
  @Override public void run(){
   try{
    int chosen=0;
    outer:for(int rate:new int[]{16000,44100,48000}){
     int min=AudioRecord.getMinBufferSize(rate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
     if(min<=0)continue;
     for(int source:new int[]{MediaRecorder.AudioSource.MIC,MediaRecorder.AudioSource.DEFAULT}){
      if(!running)return;
      if(open(source,rate,Math.max(min,rate/5*2))){chosen=rate;break outer;}
     }
    }
    if(chosen==0)throw new IllegalStateException("Android не смог открыть микрофон. Проверьте доступ к микрофону и не занят ли он другим приложением.");
    runOnUiThread(()->{if(capture==this&&running)js("nativeMicStarted("+session+")");});
    short[] data=new short[Math.max(320,chosen/25)];
    long last=0;
    while(running){
     int n=recorder.read(data,0,data.length);
     if(!running)break;
     if(n<0)throw new IllegalStateException("Ошибка чтения микрофона: "+n);
     if(n==0){SystemClock.sleep(10);continue;}
     double sum=0;
     for(int i=0;i<n;i++){double v=data[i]/32768.0;sum+=v*v;}
     double rms=Math.sqrt(sum/n);
     long now=SystemClock.elapsedRealtime();
     if(now-last>=80){last=now;runOnUiThread(()->{if(capture==this&&running)js("nativeMicLevel("+session+","+rms+")");});}
    }
   }catch(RuntimeException e){
    final String message=e.getMessage()==null?"Ошибка микрофона":e.getMessage();
    if(running)runOnUiThread(()->{if(capture==this){capture=null;wantMic=false;micError(session,message);}});
   }finally{
    synchronized(this){running=false;if(recorder!=null){try{recorder.stop();}catch(IllegalStateException ignored){}recorder.release();recorder=null;}}
   }
  }
 }
 @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results){
  super.onRequestPermissionsResult(code,permissions,results);
  if(code==7){waitingPermission=false;if(!wantMic||destroyed)return;
   if(results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED)startCapture();
   else{wantMic=false;micError(micSession,"Разрешение на микрофон отклонено");}
  }
 }
 @Override protected void onPause(){super.onPause();if(!waitingPermission){wantMic=false;stopCapture();stopSpeech();js("nativeAudioPaused()");}}
 @Override protected void onDestroy(){destroyed=true;wantMic=false;stopCapture();if(tts!=null){tts.stop();tts.shutdown();}if(web!=null)web.destroy();super.onDestroy();}
}
