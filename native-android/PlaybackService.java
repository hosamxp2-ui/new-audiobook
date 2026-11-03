package com.audiobook.player;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;

/**
 * Foreground service that owns the system media session and the media notification:
 * rewind / play-pause / forward / close buttons, cover art, and a seek bar
 * (the system draws the scrubber from the session's duration + position + SEEK_TO action).
 */
public class PlaybackService extends Service {
  interface Listener { void onAction(String name, long positionMs); }

  static class State {
    String title = "", artist = "", album = "";
    String lRew = "Rewind", lPlay = "Play", lPause = "Pause", lFwd = "Forward", lClose = "Close", lPrev = "Previous", lNext = "Next";
    long duration, position;
    float rate = 1f;
    boolean playing;
    /** seek = rewind/forward/close, tracks = prev/next + rewind/forward, tracksClose = prev/next + close */
    String mode = "seek";
    Bitmap art;
    boolean tracks() { return !"seek".equals(mode); }
  }

  static volatile Listener listener;
  static boolean isRunning() { return instance != null; }
  private static volatile PlaybackService instance;
  private static volatile State state;

  private static final String CHANNEL = "playback";
  private static final int NOTIF_ID = 4711;

  private MediaSession session;
  private NotificationManager nm;
  private boolean foreground = false;
  private final BroadcastReceiver noisy = new BroadcastReceiver() {
    @Override public void onReceive(Context c, Intent i) { emit("pause", 0); }   // headphones unplugged / Bluetooth lost
  };

  /** Called by the Capacitor plugin whenever title / position / play state changes. */
  static void update(Context c, State s) {
    if (s.art == null && state != null) s.art = state.art;
    state = s;
    PlaybackService svc = instance;
    if (svc != null) { svc.apply(); return; }
    try {
      Intent i = new Intent(c, PlaybackService.class);
      if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    } catch (Exception ignored) { }
  }

  static void stop(Context c) {
    state = null;
    PlaybackService svc = instance;
    if (svc != null) svc.shutdown(false); else c.stopService(new Intent(c, PlaybackService.class));
  }

  @Override
  public void onCreate() {
    super.onCreate();
    instance = this;
    nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    if (Build.VERSION.SDK_INT >= 26) {
      NotificationChannel ch = new NotificationChannel(CHANNEL, "Playback", NotificationManager.IMPORTANCE_LOW);
      ch.setShowBadge(false);
      nm.createNotificationChannel(ch);
    }
    session = new MediaSession(this, "AudiobookPlayer");
    session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
    session.setCallback(new MediaSession.Callback() {
      @Override public void onPlay() { emit("play", 0); }
      @Override public void onPause() { emit("pause", 0); }
      @Override public void onRewind() { emit("rewind", 0); }
      @Override public void onFastForward() { emit("forward", 0); }
      @Override public void onSeekTo(long pos) { emit("seek", pos); }
      @Override public void onStop() { dispatch("close"); }
      @Override public void onSkipToNext() { emit("next", 0); }
      @Override public void onSkipToPrevious() { emit("prev", 0); }
      @Override public void onCustomAction(String action, Bundle extras) { dispatch(action); }
    });
    session.setActive(true);
    IntentFilter f = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
    if (Build.VERSION.SDK_INT >= 33) registerReceiver(noisy, f, Context.RECEIVER_NOT_EXPORTED); else registerReceiver(noisy, f);
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    String a = intent == null ? null : intent.getAction();
    if (a != null) dispatch(a); else apply();
    return START_NOT_STICKY;
  }

  @Override public IBinder onBind(Intent intent) { return null; }

  @Override
  public void onDestroy() {
    instance = null;
    try { unregisterReceiver(noisy); } catch (Exception ignored) { }
    try { session.release(); } catch (Exception ignored) { }
    super.onDestroy();
  }

  private static void emit(String name, long pos) {
    Listener l = listener;
    if (l != null) l.onAction(name, pos);
  }

  private void dispatch(String a) {
    if ("close".equals(a)) { shutdown(true); return; }
    emit(a, 0);
  }

  private void shutdown(boolean notifyJs) {
    state = null;
    try { stopForeground(Service.STOP_FOREGROUND_REMOVE); } catch (Exception ignored) { }
    foreground = false;
    try { nm.cancel(NOTIF_ID); } catch (Exception ignored) { }
    if (notifyJs) emit("close", 0);
    stopSelf();
  }

  private void apply() {
    State s = state;
    if (s == null) {
      // started without data (race with stop): satisfy the foreground contract, then quit
      try { startForeground(NOTIF_ID, base(new State()).build()); } catch (Exception ignored) { }
      shutdown(false);
      return;
    }
    MediaMetadata.Builder mb = new MediaMetadata.Builder()
        .putString(MediaMetadata.METADATA_KEY_TITLE, s.title)
        .putString(MediaMetadata.METADATA_KEY_ARTIST, s.artist)
        .putString(MediaMetadata.METADATA_KEY_ALBUM, s.album)
        .putLong(MediaMetadata.METADATA_KEY_DURATION, s.duration);
    if (s.art != null) mb.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, s.art);
    session.setMetadata(mb.build());

    long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
        | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_REWIND | PlaybackState.ACTION_FAST_FORWARD
        | PlaybackState.ACTION_STOP;
    if (s.tracks()) actions |= PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SKIP_TO_NEXT;
    // Android 13+ builds the lock-screen / quick-settings buttons from the session, not from the notification:
    // play/pause + prev/next are standard; everything else MUST be a custom action (with our own icons).
    PlaybackState.Builder pb = new PlaybackState.Builder()
        .setActions(actions)
        .setState(s.playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
            s.position, s.playing ? s.rate : 0f, SystemClock.elapsedRealtime());
    // Slots on the system player: [custom0][prev][play][next][custom1]. When prev/next are not enabled the first
    // custom actions fill their slots. Order decides what is visible.
    String[] order = "tracksClose".equals(s.mode)
        ? new String[]{"close", "rewind", "forward"} : new String[]{"rewind", "forward", "close"};
    for (String n : order) pb.addCustomAction(custom(n, s));
    session.setPlaybackState(pb.build());

    Notification n = base(s).build();
    if (s.playing) {
      try {
        if (Build.VERSION.SDK_INT >= 29) {
          startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
          startForeground(NOTIF_ID, n);
        }
        foreground = true;
      } catch (Exception e) {
        nm.notify(NOTIF_ID, n);
      }
    } else {
      if (!foreground) {
        // service was started for a paused state: still must enter the foreground once
        try { startForeground(NOTIF_ID, n); foreground = true; } catch (Exception ignored) { }
      }
      if (foreground) {
        stopForeground(Service.STOP_FOREGROUND_DETACH);   // paused: user may swipe it away
        foreground = false;
      }
      nm.notify(NOTIF_ID, n);
    }
  }

  private Notification.Builder base(State s) {
    Notification.Builder b = Build.VERSION.SDK_INT >= 26
        ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
    b.setSmallIcon(ic("ic_pb_stat", android.R.drawable.stat_sys_headset))
        .setContentTitle(s.title)
        .setContentText(s.artist)
        .setOnlyAlertOnce(true)
        .setOngoing(s.playing)
        .setShowWhen(false)
        .setVisibility(Notification.VISIBILITY_PUBLIC)
        .setCategory(Notification.CATEGORY_TRANSPORT)
        .setDeleteIntent(pi("close", 5));
    if (s.art != null) b.setLargeIcon(s.art);
    Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
    if (open != null) {
      b.setContentIntent(PendingIntent.getActivity(this, 0, open,
          PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
    }
    String[] acts;
    int[] compact;
    if ("tracks".equals(s.mode)) { acts = new String[]{"prev", "rewind", "toggle", "forward", "next"}; compact = new int[]{0, 2, 4}; }
    else if ("tracksClose".equals(s.mode)) { acts = new String[]{"prev", "toggle", "next", "close"}; compact = new int[]{0, 1, 2}; }
    else { acts = new String[]{"rewind", "toggle", "forward", "close"}; compact = new int[]{0, 1, 2}; }
    for (int i = 0; i < acts.length; i++) b.addAction(action(acts[i], s, i + 1));
    b.setStyle(new Notification.MediaStyle()
        .setMediaSession(session.getSessionToken())
        .setShowActionsInCompactView(compact));
    return b;
  }

  private PlaybackState.CustomAction custom(String n, State s) {
    switch (n) {
      case "rewind": return new PlaybackState.CustomAction.Builder("rewind", s.lRew, ic("ic_pb_rewind", android.R.drawable.ic_media_rew)).build();
      case "forward": return new PlaybackState.CustomAction.Builder("forward", s.lFwd, ic("ic_pb_forward", android.R.drawable.ic_media_ff)).build();
      default: return new PlaybackState.CustomAction.Builder("close", s.lClose, ic("ic_pb_close", android.R.drawable.ic_menu_close_clear_cancel)).build();
    }
  }

  private Notification.Action action(String n, State s, int code) {
    int res; String label; String intentAction;
    switch (n) {
      case "prev": res = ic("ic_pb_prev", android.R.drawable.ic_media_previous); label = s.lPrev; intentAction = "prev"; break;
      case "next": res = ic("ic_pb_next", android.R.drawable.ic_media_next); label = s.lNext; intentAction = "next"; break;
      case "rewind": res = ic("ic_pb_rewind", android.R.drawable.ic_media_rew); label = s.lRew; intentAction = "rewind"; break;
      case "forward": res = ic("ic_pb_forward", android.R.drawable.ic_media_ff); label = s.lFwd; intentAction = "forward"; break;
      case "close": res = ic("ic_pb_close", android.R.drawable.ic_menu_close_clear_cancel); label = s.lClose; intentAction = "close"; break;
      default:
        res = s.playing ? ic("ic_pb_pause", android.R.drawable.ic_media_pause) : ic("ic_pb_play", android.R.drawable.ic_media_play);
        label = s.playing ? s.lPause : s.lPlay; intentAction = s.playing ? "pause" : "play";
    }
    return new Notification.Action.Builder(Icon.createWithResource(this, res), label, pi(intentAction, 10 + code)).build();
  }

  /** App drawable by name (own vector icons are generated into res/drawable by the build); framework icon as fallback. */
  private int ic(String name, int fallback) {
    int id = getResources().getIdentifier(name, "drawable", getPackageName());
    return id != 0 ? id : fallback;
  }

  private PendingIntent pi(String action, int code) {
    Intent i = new Intent(this, PlaybackService.class).setAction(action);
    return PendingIntent.getService(this, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
  }
}
