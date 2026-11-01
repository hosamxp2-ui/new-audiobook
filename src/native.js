import { Capacitor } from '@capacitor/core';
import { MediaSession } from '@capgo/capacitor-media-session';

if (Capacitor.isNativePlatform()) {
  window.__native = true;
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
}