package com.audiobook.player;

import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.webkit.WebView;
import androidx.activity.OnBackPressedCallback;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
  @Override
  public void onCreate(Bundle savedInstanceState) {
    registerPlugin(FolderAccessPlugin.class);
    registerPlugin(MediaNotificationPlugin.class);
    super.onCreate(savedInstanceState);

    // Back button: go back inside the app first (the web app collapses the player / closes pop-ups).
    // At the very first screen, do NOT close the app while audio exists: closing the activity destroys the WebView
    // that plays the sound. Send the app to the background instead so playback continues.
    getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
      @Override
      public void handleOnBackPressed() {
        WebView w = getBridge() != null ? getBridge().getWebView() : null;
        if (w != null && w.canGoBack()) { w.goBack(); return; }
        if (PlaybackService.isRunning()) { moveTaskToBack(true); return; }
        setEnabled(false);                       // nothing playing: normal exit
        getOnBackPressedDispatcher().onBackPressed();
      }
    });

    // Android 13+: the playback notification needs this permission to be visible
    if (Build.VERSION.SDK_INT >= 33
        && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
      requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 4711);
    }
  }

  @Override
  public void onPause() {
    super.onPause();
    // keep the page's timers (progress saving, next-track logic) running while the screen is off
    try { getBridge().getWebView().resumeTimers(); } catch (Exception ignored) { }
  }

  @Override
  public void onDestroy() {
    // the app is really closing (swiped away / finished): the audio is gone, so remove the notification too
    if (isFinishing()) PlaybackService.stop(getApplicationContext());
    super.onDestroy();
  }
}
