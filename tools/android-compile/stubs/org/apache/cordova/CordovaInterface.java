package org.apache.cordova;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import java.util.concurrent.ExecutorService;

public interface CordovaInterface {
    Activity getActivity();
    Context getContext();
    ExecutorService getThreadPool();
    void startActivityForResult(CordovaPlugin command, Intent intent, int requestCode);
    void setActivityResultCallback(CordovaPlugin plugin);
    Object onMessage(String id, Object data);
    boolean hasPermission(String permission);
    void requestPermission(CordovaPlugin plugin, int requestCode, String permission);
    void requestPermissions(CordovaPlugin plugin, int requestCode, String[] permissions);
}
