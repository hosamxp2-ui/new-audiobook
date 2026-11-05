// Bundled by esbuild into www/native.js (see package.json "build:web").
import { Capacitor, registerPlugin } from '@capacitor/core';

const FolderAccess = registerPlugin('FolderAccess');           // native-android/FolderAccessPlugin.java
const MediaNotification = registerPlugin('MediaNotification'); // native-android/MediaNotificationPlugin.java

if (Capacitor.isNativePlatform()) {
  window.__native = true;

  /* ---- navigator.mediaSession -> native media notification (rewind / play-pause / forward / close + seek bar) ---- */
  window.MediaMetadata = window.MediaMetadata || function (o) { Object.assign(this, o); };
  let meta = null, state = 'none', pos = { duration: 0, position: 0, playbackRate: 1 };
  let posAt = 0, lastPush = 0, artSent = '', timer = 0;
  const handlers = {};

  const push = () => {                       // coalesce metadata + state + position changes into one native call
    clearTimeout(timer);
    timer = setTimeout(() => {
      lastPush = Date.now();
      if (!meta || state === 'none') { artSent = ''; MediaNotification.stop().catch(() => {}); return; }
      const art = meta.artwork && meta.artwork.length ? meta.artwork[meta.artwork.length - 1].src : '';
      const o = {
        title: meta.title || '', artist: meta.artist || '', album: meta.album || '',
        duration: Math.round((pos.duration || 0) * 1000),
        position: Math.round((pos.position || 0) * 1000),
        rate: pos.playbackRate || 1,
        playing: state === 'playing',
        mode: window.__notifMode || ((handlers.previoustrack || handlers.nexttrack) ? 'tracks' : 'seek')
      };
      if (art && art !== artSent) { o.artwork = art; artSent = art; }   // send the cover only when it changes
      MediaNotification.update(o).catch(() => {});
    }, 80);
  };

  Object.defineProperty(navigator, 'mediaSession', {
    configurable: true,
    value: {
      get metadata() { return meta; },
      set metadata(m) { meta = m; push(); },
      get playbackState() { return state; },
      set playbackState(s) { state = s; posAt = Date.now(); push(); },
      setActionHandler(action, fn) { if (typeof fn === 'function') handlers[action] = fn; else delete handlers[action]; },
      setPositionState(p) {
        if (!p) return;
        const now = Date.now();
        const predicted = pos.position + (state === 'playing' ? (now - posAt) / 1000 * (pos.playbackRate || 1) : 0);
        const durChanged = (p.duration || 0) !== pos.duration;
        pos = { duration: p.duration || 0, position: p.position || 0, playbackRate: p.playbackRate || 1 };
        posAt = now;
        // the system interpolates the seek bar itself; only resync after seeks, drift or a new duration
        if (durChanged || Math.abs(predicted - pos.position) > 1.5 || now - lastPush > 15000) push();
      }
    }
  });

  const fire = (a, d) => { const h = handlers[a]; if (h) { try { h(d || {}); } catch (e) {} } };
  MediaNotification.addListener('action', (e) => {
    switch (e.name) {
      case 'play': fire('play'); break;
      case 'pause': fire('pause'); break;
      case 'rewind': fire('seekbackward'); break;
      case 'forward': fire('seekforward'); break;
      case 'seek': fire('seekto', { seekTime: (e.position || 0) / 1000 }); break;
      case 'next': fire('nexttrack'); break;
      case 'prev': fire('previoustrack'); break;
      case 'close': fire('stop'); break;
    }
  });

  /* ---- diagnostics used by Settings -> About -> Test notification ---- */
  window.NativeMedia = {
    status: () => MediaNotification.status(),
    openSettings: () => MediaNotification.openSettings(),
    leave: () => MediaNotification.leave(),
    test: async () => {
      await MediaNotification.update({ title: 'Notification test', artist: 'Audiobook Player',
        duration: 60000, position: 15000, rate: 1, playing: true, mode: window.__notifMode || 'tracks' });
      await new Promise((r) => setTimeout(r, 1200));
      return MediaNotification.status();
    }
  };

  /* ---- custom folders / SD card / USB (Storage Access Framework) ---- */
  let basePromise = null;
  window.NativeFolders = {
    pickFolder: (hint) => FolderAccess.pickFolder({ hint: hint || '' }),
    hasAccess: (uri) => FolderAccess.hasAccess({ uri }),
    release: (uri) => FolderAccess.release({ uri }),
    cancel: () => FolderAccess.cancelScan(),
    pickBackupFolder: (hint) => FolderAccess.pickBackupFolder({ hint: hint || '' }),
    writeBackup: (uri, name, data) => FolderAccess.writeBackup({ uri, name, data }),
    readBackup: (uri, name) => FolderAccess.readBackup({ uri, name }),
    baseUrl: () => basePromise || (basePromise = FolderAccess.baseUrl().then((r) => r.base)),
    async listAudio(uri, onChunk) {
      const id = String(Date.now());
      const handle = await FolderAccess.addListener('chunk', (d) => { if (d.id === id) onChunk(d); });
      try { return await FolderAccess.listAudio({ uri, id }); } finally { handle.remove(); }
    }
  };
}
