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
 private static final String GPT_KEY_ALIAS="BikerVoiceGatewayTokenV1";
 private static final String DEFAULT_ENDPOINT="https://bicker-voice.onrender.com/chat";
 private javax.crypto.SecretKey gatewayKey() throws Exception {
  java.security.KeyStore ks=java.security.KeyStore.getInstance("AndroidKeyStore");ks.load(null);
  java.security.Key existing=ks.getKey(GPT_KEY_ALIAS,null);
  if(existing instanceof javax.crypto.SecretKey)return (javax.crypto.SecretKey)existing;
  javax.crypto.KeyGenerator kg=javax.crypto.KeyGenerator.getInstance("AES","AndroidKeyStore");
  kg.init(new android.security.keystore.KeyGenParameterSpec.Builder(GPT_KEY_ALIAS,android.security.keystore.KeyProperties.PURPOSE_ENCRYPT|android.security.keystore.KeyProperties.PURPOSE_DECRYPT).setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build());
  return kg.generateKey();
 }
 private static void validateGateway(String endpoint,String token) throws Exception {
  URL url=new URL(endpoint);
  if(!"https".equalsIgnoreCase(url.getProtocol())||url.getUserInfo()!=null||url.getHost().isEmpty()||!"/chat".equals(url.getPath())||url.getQuery()!=null||url.getRef()!=null)throw new IllegalArgumentException("Нужен HTTPS-адрес с /chat");
  if(token.length()<24||token.length()>512||!token.matches("[!-~]+"))throw new IllegalArgumentException("Некорректный токен сервера");
 }
 public class VoiceBridge {
  @JavascriptInterface public synchronized String saveGptSettings(String endpoint,String token){
   try{
    validateGateway(endpoint,token);
    android.content.SharedPreferences prefs=getPreferences(MODE_PRIVATE);
    javax.crypto.Cipher cipher=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(javax.crypto.Cipher.ENCRYPT_MODE,gatewayKey());
    cipher.updateAAD(endpoint.getBytes(StandardCharsets.UTF_8));
    String encrypted=android.util.Base64.encodeToString(cipher.doFinal(token.getBytes(StandardCharsets.UTF_8)),android.util.Base64.NO_WRAP);
    String iv=android.util.Base64.encodeToString(cipher.getIV(),android.util.Base64.NO_WRAP);
    if(!prefs.edit().putString("gatewayEndpoint",endpoint).putString("gatewayCipher",encrypted).putString("gatewayIv",iv).commit())return "Не удалось записать настройки";
    return "";
   }catch(IllegalArgumentException e){return e.getMessage();}catch(Exception e){return "Не удалось зашифровать настройки на устройстве";}
  }
  @JavascriptInterface public synchronized String loadGptSettings(){
   JSONObject result=new JSONObject();
   try{
    android.content.SharedPreferences prefs=getPreferences(MODE_PRIVATE);
    String endpoint=prefs.getString("gatewayEndpoint",DEFAULT_ENDPOINT);
    result.put("endpoint",endpoint);result.put("token","");
    String encrypted=prefs.getString("gatewayCipher","");
    if(!encrypted.isEmpty()){
     javax.crypto.Cipher cipher=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
     cipher.init(javax.crypto.Cipher.DECRYPT_MODE,gatewayKey(),new javax.crypto.spec.GCMParameterSpec(128,android.util.Base64.decode(prefs.getString("gatewayIv",""),android.util.Base64.NO_WRAP)));
     cipher.updateAAD(endpoint.getBytes(StandardCharsets.UTF_8));
     result.put("token",new String(cipher.doFinal(android.util.Base64.decode(encrypted,android.util.Base64.NO_WRAP)),StandardCharsets.UTF_8));
    }
   }catch(Exception e){try{result.put("error","Не удалось восстановить токен. Введи его заново.");}catch(org.json.JSONException ignored){}}
   return result.toString();
  }
  @JavascriptInterface public void askGpt(int seq,String endpoint,String token,String messages){runOnUiThread(()->{
   if(destroyed)return;final int generation=++gptGeneration;
   new Thread(()->{
    HttpsURLConnection conn=null;
    try{
     URL url=new URL(endpoint);
     validateGateway(endpoint,token);
     if(messages.length()>24000)throw new IllegalArgumentException("Слишком длинный запрос");
     conn=(HttpsURLConnection)url.openConnection();conn.setInstanceFollowRedirects(false);conn.setConnectTimeout(15000);conn.setReadTimeout(100000);conn.setRequestMethod("POST");conn.setDoOutput(true);
     conn.setRequestProperty("Authorization","Bearer "+token);conn.setRequestProperty("Content-Type","application/json; charset=utf-8");
     JSONObject payload=new JSONObject();payload.put("messages",new JSONArray(messages));
     try(java.io.OutputStream out=conn.getOutputStream()){out.write(payload.toString().getBytes(StandardCharsets.UTF_8));}
     int status=conn.getResponseCode();if(status!=200){String detail=status==401?"Токен не совпадает с BIKER_ACCESS_TOKEN в Render":status==404?"Проверь адрес /chat":status==429?"Лимит запросов или API":status==502?"Сервер не получил ответ от OpenAI. Проверь API-ключ, оплату и доступ к модели":status==503?"Сервер запускается. Повтори через минуту":"Ошибка сервера";throw new IllegalStateException("HTTP "+status+": "+detail);}
     ByteArrayOutputStream buffer=new ByteArrayOutputStream();try(InputStream in=conn.getInputStream()){byte[] bytes=new byte[4096];int n;while((n=in.read(bytes))!=-1){if(buffer.size()+n>65536)throw new IllegalStateException("Ответ слишком большой");buffer.write(bytes,0,n);}}
     String reply=new JSONObject(new String(buffer.toByteArray(),StandardCharsets.UTF_8)).getString("reply");
     if(reply.trim().isEmpty())throw new IllegalStateException("Пустой ответ GPT");
     runOnUiThread(()->{if(!destroyed&&generation==gptGeneration)js("nativeGptReply("+seq+","+JSONObject.quote(reply)+")");});
    }catch(Exception e){String message=e instanceof IllegalStateException||e instanceof IllegalArgumentException?e.getMessage():e instanceof java.net.SocketTimeoutException?"Сервер не ответил вовремя. Повтори запрос":e instanceof java.net.UnknownHostException?"Не найден адрес сервера. Проверь интернет и URL":e instanceof javax.net.ssl.SSLException?"Ошибка защищённого HTTPS-соединения":"Не удалось связаться с сервером GPT";runOnUiThread(()->{if(!destroyed&&generation==gptGeneration)js("nativeGptError("+seq+","+JSONObject.quote(message)+")");});}
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
