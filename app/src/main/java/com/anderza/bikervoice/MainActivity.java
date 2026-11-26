package com.anderza.bikervoice;
import android.Manifest;
import android.app.Activity;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.webkit.*;
import android.content.pm.PackageManager;
import android.webkit.JavascriptInterface;
import java.util.Locale;
public class MainActivity extends Activity {
 WebView web; TextToSpeech tts; PermissionRequest pending;
 @Override public void onCreate(Bundle b){super.onCreate(b);
 web=new WebView(this);setContentView(web);
 web.getSettings().setJavaScriptEnabled(true);
 web.getSettings().setDomStorageEnabled(true);
 web.getSettings().setMediaPlaybackRequiresUserGesture(false);
 web.addJavascriptInterface(new VoiceBridge(),"AndroidVoice");
 tts=new TextToSpeech(this,status->{if(status==TextToSpeech.SUCCESS)tts.setLanguage(new Locale("ru","RU"));});
 web.setWebViewClient(new WebViewClient());
 web.setWebChromeClient(new WebChromeClient(){
 @Override public void onPermissionRequest(PermissionRequest req){runOnUiThread(()->{
 boolean mic=false;for(String r:req.getResources())if(r.equals(PermissionRequest.RESOURCE_AUDIO_CAPTURE))mic=true;
 if(!mic){req.deny();return;}
 if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)req.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
 else{pending=req;requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},7);}
 });}
 });
 web.loadUrl("file:///android_asset/index.html");
 }
 public class VoiceBridge{
 @JavascriptInterface public void speak(String text){runOnUiThread(()->{
 web.evaluateJavascript("nativeSpeechStart()",null);
 tts.setOnUtteranceProgressListener(new android.speech.tts.UtteranceProgressListener(){
 @Override public void onStart(String id){}
 @Override public void onDone(String id){runOnUiThread(()->web.evaluateJavascript("nativeSpeechEnd()",null));}
 @Override public void onError(String id){runOnUiThread(()->web.evaluateJavascript("nativeSpeechEnd()",null));}
 });
 tts.speak(text,TextToSpeech.QUEUE_FLUSH,null,"biker");
 });}
 @JavascriptInterface public void stop(){runOnUiThread(()->tts.stop());}
 }
 @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] results){
 super.onRequestPermissionsResult(requestCode,permissions,results);
 if(requestCode==7&&pending!=null){if(results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED)pending.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});else pending.deny();pending=null;}
 }
 @Override protected void onDestroy(){if(tts!=null){tts.stop();tts.shutdown();}web.destroy();super.onDestroy();}
}