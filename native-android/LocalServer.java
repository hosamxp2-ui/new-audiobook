package com.audiobook.player;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tiny HTTP server bound to 127.0.0.1 with a random secret in every URL.
 * It lets the WebView's <audio> element stream (and seek in) files that Android only exposes as content:// URIs.
 */
class LocalServer {
  private final ServerSocket ss;
  private final ContentResolver cr;
  private final String secret;
  private final ExecutorService pool = Executors.newCachedThreadPool();
  private volatile boolean running = true;
  private final Map<String, Uri> cache = Collections.synchronizedMap(
      new LinkedHashMap<String, Uri>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Uri> e) { return size() > 2000; }
      });

  LocalServer(ContentResolver cr) throws IOException {
    this.cr = cr;
    this.ss = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
    byte[] b = new byte[16];
    new SecureRandom().nextBytes(b);
    StringBuilder sb = new StringBuilder();
    for (byte x : b) sb.append(String.format("%02x", x));
    this.secret = sb.toString();
    Thread t = new Thread(() -> {
      while (running) {
        try {
          final Socket s = ss.accept();
          pool.execute(() -> handle(s));
        } catch (IOException e) {
          if (!running) break;
        }
      }
    }, "audio-local-server");
    t.setDaemon(true);
    t.start();
  }

  String base() { return "http://127.0.0.1:" + ss.getLocalPort() + "/" + secret; }

  void stop() {
    running = false;
    try { ss.close(); } catch (IOException ignored) { }
    pool.shutdownNow();
  }

  private void handle(Socket sock) {
    try {
      sock.setSoTimeout(30000);
      BufferedReader br = new BufferedReader(new InputStreamReader(sock.getInputStream(), "ISO-8859-1"));
      String req = br.readLine();
      if (req == null) return;
      String[] p = req.split(" ");
      if (p.length < 2) return;
      String range = null;
      String line;
      while ((line = br.readLine()) != null && !line.isEmpty()) {
        if (line.regionMatches(true, 0, "range:", 0, 6)) range = line.substring(6).trim();
      }
      OutputStream out = new BufferedOutputStream(sock.getOutputStream());
      boolean head = "HEAD".equals(p[0]);
      Uri u = Uri.parse("http://x" + p[1]);
      List<String> segs = u.getPathSegments();
      if (segs.size() < 2 || !secret.equals(segs.get(0))) { status(out, 403, "Forbidden"); return; }
      String rel = u.getQueryParameter("p");
      Uri doc = resolve(u.getQueryParameter("t"), rel);
      if (doc == null) { status(out, 404, "Not Found"); return; }

      try (ParcelFileDescriptor pfd = cr.openFileDescriptor(doc, "r")) {
        if (pfd == null) { status(out, 404, "Not Found"); return; }
        long len = pfd.getStatSize();
        if (len < 0) { status(out, 500, "Unknown size"); return; }
        long start = 0;
        long end = len - 1;
        boolean partial = false;
        if (range != null && range.startsWith("bytes=") && len > 0) {
          String r = range.substring(6).split(",")[0].trim();
          int dash = r.indexOf('-');
          if (dash >= 0) {
            String a = r.substring(0, dash);
            String b = r.substring(dash + 1);
            try {
              if (a.isEmpty()) {
                start = Math.max(0, len - Long.parseLong(b));
              } else {
                start = Long.parseLong(a);
                if (!b.isEmpty()) end = Math.min(len - 1, Long.parseLong(b));
              }
              partial = true;
            } catch (NumberFormatException ignored) { partial = false; start = 0; end = len - 1; }
          }
          if (partial && start >= len) {
            out.write(("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */" + len
                + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes("ISO-8859-1"));
            out.flush();
            return;
          }
        }
        long count = Math.max(0, end - start + 1);
        StringBuilder h = new StringBuilder();
        h.append(partial ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n");
        h.append("Content-Type: ").append(mime(rel)).append("\r\n");
        h.append("Accept-Ranges: bytes\r\n");
        if (partial) h.append("Content-Range: bytes ").append(start).append('-').append(end).append('/').append(len).append("\r\n");
        h.append("Content-Length: ").append(count).append("\r\n");
        h.append("Access-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n");
        out.write(h.toString().getBytes("ISO-8859-1"));
        if (!head && count > 0) {
          FileChannel ch = new FileInputStream(pfd.getFileDescriptor()).getChannel();
          ch.position(start);
          ByteBuffer buf = ByteBuffer.allocate(65536);
          long left = count;
          while (left > 0) {
            buf.clear();
            if (left < buf.capacity()) buf.limit((int) left);
            int n = ch.read(buf);
            if (n <= 0) break;
            out.write(buf.array(), 0, n);
            left -= n;
          }
        }
        out.flush();
      }
    } catch (Exception ignored) {
      // client closed the connection (normal when seeking) or the file vanished
    } finally {
      try { sock.close(); } catch (IOException ignored) { }
    }
  }

  private void status(OutputStream out, int code, String text) throws IOException {
    out.write(("HTTP/1.1 " + code + " " + text + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
        .getBytes("ISO-8859-1"));
    out.flush();
  }

  private Uri resolve(String treeS, String rel) {
    if (treeS == null || rel == null) return null;
    String key = treeS + "|" + rel;
    Uri hit = cache.get(key);
    if (hit != null) return hit;
    Uri tree = Uri.parse(treeS);
    String id = DocumentsContract.getTreeDocumentId(tree);
    for (String seg : rel.split("/")) {
      id = childId(tree, id, seg);
      if (id == null) return null;
    }
    Uri d = DocumentsContract.buildDocumentUriUsingTree(tree, id);
    cache.put(key, d);
    return d;
  }

  private String childId(Uri tree, String parentId, String name) {
    Uri kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
    try (Cursor c = cr.query(kids, new String[]{Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME},
        null, null, null)) {
      while (c != null && c.moveToNext()) {
        if (name.equals(c.getString(1))) return c.getString(0);
      }
    } catch (Exception ignored) { }
    return null;
  }

  private static String mime(String name) {
    String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
    if (n.endsWith(".mp3")) return "audio/mpeg";
    if (n.endsWith(".m4a") || n.endsWith(".m4b") || n.endsWith(".aac") || n.endsWith(".mp4")) return "audio/mp4";
    if (n.endsWith(".ogg") || n.endsWith(".opus")) return "audio/ogg";
    if (n.endsWith(".wav")) return "audio/wav";
    if (n.endsWith(".flac")) return "audio/flac";
    if (n.endsWith(".wma")) return "audio/x-ms-wma";
    if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
    if (n.endsWith(".png")) return "image/png";
    if (n.endsWith(".webp")) return "image/webp";
    return "application/octet-stream";
  }
}
