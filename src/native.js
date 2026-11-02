// Bundled by esbuild into www/native.js (see package.json "build:web").
import { Capacitor, registerPlugin } from '@capacitor/core';
import { MediaSession } from '@capgo/capacitor-media-session';

const FolderAccess = registerPlugin('FolderAccess');   // native plugin: native-android/FolderAccessPlugin.java

if (Capacitor.isNativePlatform()) {
  window.__native = true;

  /* ---- lock screen / notification (Media Session) ---- */
  window.MediaMetadata = window.MediaMetadata || function (o) { Object.assign(this, o); };
  let meta = null, state = 'none', lastPos = 0;
  const safe = (fn) => { try { const p = fn(); if (p && p.catch) p.catch(() => {}); } catch (e) {} };
  Object.defineProperty(navigator, 'mediaSession', {
    configurable: true,
    value: {
      get metadata() { return meta; },
      set metadata(m) {
        meta = m;
        if (m) safe(() => MediaSession.setMetadata({
          title: m.title || '', artist: m.artist || '', album: m.album || '',
          artwork: (m.artwork || []).slice(-1)
        }));
      },
      get playbackState() { return state; },
      set playbackState(s) { state = s; safe(() => MediaSession.setPlaybackState({ playbackState: s })); },
      setActionHandler(action, handler) {
        if (typeof handler === 'function') safe(() => MediaSession.setActionHandler({ action }, handler));
      },
      setPositionState(p) {
        const now = Date.now();
        if (!p || now - lastPos < 1000) return;
        lastPos = now;
        safe(() => MediaSession.setPositionState(p));
      }
    }
  });

  /* ---- custom folders / SD card / USB (Storage Access Framework) ---- */
  let basePromise = null;
  window.NativeFolders = {
    pickFolder: (hint) => FolderAccess.pickFolder({ hint: hint || '' }),
    hasAccess: (uri) => FolderAccess.hasAccess({ uri }),
    release: (uri) => FolderAccess.release({ uri }),
    cancel: () => FolderAccess.cancelScan(),
    baseUrl: () => basePromise || (basePromise = FolderAccess.baseUrl().then((r) => r.base)),
    async listAudio(uri, onChunk) {
      const id = String(Date.now());
      const handle = await FolderAccess.addListener('chunk', (d) => { if (d.id === id) onChunk(d); });
      try { return await FolderAccess.listAudio({ uri, id }); } finally { handle.remove(); }
    }
  };
}
