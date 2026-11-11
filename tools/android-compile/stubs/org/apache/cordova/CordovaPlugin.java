// Stub of the Cordova Android bridge, for javac -proc:none only. Never compiled into the plugin.
package org.apache.cordova;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import org.json.JSONArray;

public class CordovaPlugin {
    public CordovaInterface cordova;
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext)
            throws org.json.JSONException { return false; }
    public void initialize(CordovaInterface cordova, CordovaWebView webView) {}
    protected void pluginInitialize() {}
    public void onActivityResult(int requestCode, int resultCode, Intent intent) {}
    public void onRequestPermissionResult(int requestCode, String[] permissions, int[] grantResults)
            throws org.json.JSONException {}
    public void onDestroy() {}
    public void onPause(boolean multitasking) {}
    public void onResume(boolean multitasking) {}
    public Context getContext() { return null; }
    public boolean hasPermisssion() { return true; }
    public void requestPermissions(int requestCode) {}
}
