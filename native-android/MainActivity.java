package com.audiobook.player;

import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
  @Override
  public void onCreate(Bundle savedInstanceState) {
    registerPlugin(FolderAccessPlugin.class);
    registerPlugin(MediaNotificationPlugin.class);
    super.onCreate(savedInstanceState);
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
}
