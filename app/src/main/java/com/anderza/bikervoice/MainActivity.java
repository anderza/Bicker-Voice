package com.anderza.bikervoice;

import android.Manifest;
import android.app.Activity;
import android.os.Bundle;
import android.os.SystemClock;
import android.content.pm.PackageManager;
import android.speech.SpeechRecognizer;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.content.Intent;
import java.net.URL;
import javax.net.ssl.HttpsURLConnection;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
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
 private SpeechRecognizer recognizer;
 private volatile int gptGeneration=0;
 private void js(String code){ if(!destroyed && web!=null) web.evaluateJavascript(code,null); }
 private void micError(int session,String message){ if(session==micSession)js("nativeMicError("+session+","+JSONObject.quote(message)+")"); }
 @Override public void onCreate(Bundle b){
  super.onCreate(b);
  web=new WebView(this);setContentView(web);
  web.getSettings().setJavaScriptEnabled(true);
  web.getSettings().setDomStorageEnabled(true);
  web.getSettings().setMediaPlaybackRequiresUserGesture(false);
  web.addJavascriptInterface(new VoiceBridge(),"AndroidVoice");
  web.getSettings().setAllowFileAccess(false);
  web.getSettings().setAllowContentAccess(false);
  web.setWebViewClient(new WebViewClient(){@Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){return true;}});
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
  @JavascriptInterface public void askGpt(int seq,String endpoint,String token,String messages){runOnUiThread(()->{
   if(destroyed)return;final int generation=++gptGeneration;
   new Thread(()->{
    HttpsURLConnection conn=null;
    try{
     URL url=new URL(endpoint);
     if(!"https".equalsIgnoreCase(url.getProtocol())||url.getUserInfo()!=null||url.getHost().isEmpty())throw new IllegalArgumentException("Нужен HTTPS-адрес сервера");
     if(token.isEmpty()||token.length()>512||messages.length()>24000)throw new IllegalArgumentException("Некорректные настройки или слишком длинный запрос");
     conn=(HttpsURLConnection)url.openConnection();conn.setInstanceFollowRedirects(false);conn.setConnectTimeout(15000);conn.setReadTimeout(60000);conn.setRequestMethod("POST");conn.setDoOutput(true);
     conn.setRequestProperty("Authorization","Bearer "+token);conn.setRequestProperty("Content-Type","application/json; charset=utf-8");
     JSONObject payload=new JSONObject();payload.put("messages",new JSONArray(messages));
     try(java.io.OutputStream out=conn.getOutputStream()){out.write(payload.toString().getBytes(StandardCharsets.UTF_8));}
     int status=conn.getResponseCode();if(status!=200)throw new IllegalStateException("Сервер GPT: HTTP "+status);
     ByteArrayOutputStream buffer=new ByteArrayOutputStream();try(InputStream in=conn.getInputStream()){byte[] bytes=new byte[4096];int n;while((n=in.read(bytes))!=-1){if(buffer.size()+n>65536)throw new IllegalStateException("Ответ слишком большой");buffer.write(bytes,0,n);}}
     String reply=new JSONObject(new String(buffer.toByteArray(),StandardCharsets.UTF_8)).getString("reply");
     if(reply.trim().isEmpty())throw new IllegalStateException("Пустой ответ GPT");
     runOnUiThread(()->{if(!destroyed&&generation==gptGeneration)js("nativeGptReply("+seq+","+JSONObject.quote(reply)+")");});
    }catch(Exception e){String message=e instanceof IllegalStateException||e instanceof IllegalArgumentException?e.getMessage():"Не удалось связаться с сервером GPT";runOnUiThread(()->{if(!destroyed&&generation==gptGeneration)js("nativeGptError("+seq+","+JSONObject.quote(message)+")");});}
    finally{if(conn!=null)conn.disconnect();}
   },"Biker-GPT").start();
  });}
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
  @JavascriptInterface public void stop(){runOnUiThread(()->{gptGeneration++;wantMic=false;stopCapture();stopSpeech();});}
 }
 private void stopSpeech(){speechSession++;if(tts!=null)tts.stop();}
 private void stopCapture(){
  SpeechRecognizer old=recognizer;recognizer=null;if(old!=null){old.cancel();old.destroy();}
 }
 private void startCapture(){
  if(!wantMic||destroyed)return;stopCapture();final int session=micSession;
  if(!SpeechRecognizer.isRecognitionAvailable(this)){wantMic=false;micError(session,"Сервис распознавания речи Android недоступен");return;}
  try{
   final SpeechRecognizer sr=SpeechRecognizer.createSpeechRecognizer(this);recognizer=sr;
   sr.setRecognitionListener(new RecognitionListener(){
    private boolean active(){return recognizer==sr&&wantMic&&!destroyed;}
    @Override public void onReadyForSpeech(Bundle p){if(active())js("nativeMicStarted("+session+")");}
    @Override public void onBeginningOfSpeech(){}
    @Override public void onRmsChanged(float rms){if(active())js("nativeMicLevel("+session+","+Math.max(0,Math.min(1,(rms+2)/40.0))+")");}
    @Override public void onBufferReceived(byte[] bytes){}
    @Override public void onEndOfSpeech(){}
    @Override public void onError(int code){if(active()){wantMic=false;stopCapture();micError(session,"Распознавание речи: ошибка "+code+". Попробуй ещё раз.");}}
    @Override public void onResults(Bundle result){if(!active())return;ArrayList<String> list=result.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);wantMic=false;stopCapture();if(list==null||list.isEmpty())micError(session,"Не расслышал. Попробуй ещё раз.");else js("nativeRecognized("+session+","+JSONObject.quote(list.get(0))+")");}
    @Override public void onPartialResults(Bundle p){}
    @Override public void onEvent(int event,Bundle p){}
   });
   Intent intent=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE,"ru-RU");intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,1);sr.startListening(intent);
  }catch(RuntimeException e){wantMic=false;stopCapture();micError(session,"Не удалось запустить распознавание речи");}
 }
 @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results){
  super.onRequestPermissionsResult(code,permissions,results);
  if(code==7){waitingPermission=false;if(!wantMic||destroyed)return;
   if(results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED)startCapture();
   else{wantMic=false;micError(micSession,"Разрешение на микрофон отклонено");}
  }
 }
 @Override protected void onPause(){super.onPause();if(!waitingPermission){gptGeneration++;wantMic=false;stopCapture();stopSpeech();js("nativeAudioPaused()");}}
 @Override protected void onDestroy(){destroyed=true;wantMic=false;stopCapture();if(tts!=null){tts.stop();tts.shutdown();}if(web!=null)web.destroy();super.onDestroy();}
}
