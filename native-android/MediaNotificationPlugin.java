package com.audiobook.player;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/** Bridge between the web app's player and PlaybackService (media notification + lock screen controls). */
@CapacitorPlugin(name = "MediaNotification")
public class MediaNotificationPlugin extends Plugin {

  @Override
  public void load() {
    PlaybackService.listener = (name, pos) -> {
      JSObject o = new JSObject();
      o.put("name", name);
      o.put("position", pos);
      notifyListeners("action", o);
    };
  }

  @PluginMethod
  public void update(PluginCall call) {
    PlaybackService.State s = new PlaybackService.State();
    s.title = call.getString("title", "");
    s.artist = call.getString("artist", "");
    s.album = call.getString("album", "");
    s.duration = (long) (double) call.getDouble("duration", 0.0);
    s.position = (long) (double) call.getDouble("position", 0.0);
    s.rate = (float) (double) call.getDouble("rate", 1.0);
    s.playing = call.getBoolean("playing", false);
    s.lRew = call.getString("lRew", s.lRew);
    s.lPlay = call.getString("lPlay", s.lPlay);
    s.lPause = call.getString("lPause", s.lPause);
    s.lFwd = call.getString("lFwd", s.lFwd);
    s.lClose = call.getString("lClose", s.lClose);
    String art = call.getString("artwork");
    if (art != null && !art.isEmpty()) s.art = decode(art);
    PlaybackService.update(getContext(), s);
    call.resolve();
  }

  @PluginMethod
  public void stop(PluginCall call) {
    PlaybackService.stop(getContext());
    call.resolve();
  }

  /** data:image/...;base64,XXXX  ->  Bitmap (max 320 px, keeps memory small) */
  private Bitmap decode(String dataUrl) {
    try {
      int comma = dataUrl.indexOf(',');
      byte[] raw = Base64.decode(comma >= 0 ? dataUrl.substring(comma + 1) : dataUrl, Base64.DEFAULT);
      BitmapFactory.Options bounds = new BitmapFactory.Options();
      bounds.inJustDecodeBounds = true;
      BitmapFactory.decodeByteArray(raw, 0, raw.length, bounds);
      int sample = 1;
      while (bounds.outWidth / (sample * 2) >= 320) sample *= 2;
      BitmapFactory.Options o = new BitmapFactory.Options();
      o.inSampleSize = sample;
      return BitmapFactory.decodeByteArray(raw, 0, raw.length, o);
    } catch (Exception e) {
      return null;
    }
  }
}
