package com.audiobook.player;

import android.app.Activity;
import android.content.Intent;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import androidx.activity.result.ActivityResult;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.regex.Pattern;

/** Lets the web app pick any folder (internal storage, SD card, USB) and keep access after restarts. */
@CapacitorPlugin(name = "FolderAccess")
public class FolderAccessPlugin extends Plugin {
  private static final Pattern AUDIO = Pattern.compile(
      ".*\\.(mp3|m4a|m4b|aac|ogg|opus|wav|flac|wma)$", Pattern.CASE_INSENSITIVE);
  private static final Pattern COVER = Pattern.compile(
      "^(cover|folder|front|albumart.*)\\.(jpe?g|png|webp)$", Pattern.CASE_INSENSITIVE);
  private static final String[] COLS = {
      Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
      Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED};

  private volatile boolean cancel = false;
  private LocalServer server;

  @PluginMethod
  public void pickFolder(PluginCall call) {
    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
    String hint = call.getString("hint");
    if (hint != null && !hint.isEmpty()) {
      try { i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(hint)); } catch (Exception ignored) { }
    }
    startActivityForResult(call, i, "onPicked");
  }

  @ActivityCallback
  private void onPicked(PluginCall call, ActivityResult result) {
    if (call == null) return;
    if (result.getResultCode() != Activity.RESULT_OK
        || result.getData() == null || result.getData().getData() == null) {
      call.reject("cancelled");
      return;
    }
    Uri tree = result.getData().getData();
    try {
      getContext().getContentResolver().takePersistableUriPermission(
          tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
    } catch (Exception ignored) { }
    JSObject o = new JSObject();
    o.put("uri", tree.toString());
    o.put("name", nameOf(tree));
    call.resolve(o);
  }

  /** Folder for automatic backups: same picker but with WRITE access that survives restarts. */
  @PluginMethod
  public void pickBackupFolder(PluginCall call) {
    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
    String hint = call.getString("hint");
    if (hint != null && !hint.isEmpty()) {
      try { i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(hint)); } catch (Exception ignored) { }
    }
    startActivityForResult(call, i, "onPickedBackup");
  }

  @ActivityCallback
  private void onPickedBackup(PluginCall call, ActivityResult result) {
    if (call == null) return;
    if (result.getResultCode() != Activity.RESULT_OK
        || result.getData() == null || result.getData().getData() == null) {
      call.reject("cancelled");
      return;
    }
    Uri tree = result.getData().getData();
    try {
      getContext().getContentResolver().takePersistableUriPermission(
          tree, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    } catch (Exception ignored) { }
    JSObject o = new JSObject();
    o.put("uri", tree.toString());
    o.put("name", nameOf(tree));
    call.resolve(o);
  }

  private Uri findChild(android.content.ContentResolver cr, Uri tree, String name) {
    try {
      Uri kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
      try (Cursor c = cr.query(kids, new String[]{Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
        while (c != null && c.moveToNext()) {
          if (name.equals(c.getString(1))) return DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0));
        }
      }
    } catch (Exception ignored) { }
    return null;
  }

  /** Creates or overwrites <name> inside the chosen folder. */
  @PluginMethod
  public void writeBackup(PluginCall call) {
    String uriS = call.getString("uri");
    String name = call.getString("name", "audiobook-backup.json");
    String data = call.getString("data");
    String mime = call.getString("mime", "application/json");
    if (uriS == null || data == null) { call.reject("uri and data required"); return; }
    try {
      android.content.ContentResolver cr = getContext().getContentResolver();
      Uri tree = Uri.parse(uriS);
      Uri doc = findChild(cr, tree, name);
      if (doc == null) {
        Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        doc = DocumentsContract.createDocument(cr, parent, mime, name);
      }
      if (doc == null) { call.reject("could not create file"); return; }
      try (OutputStream os = cr.openOutputStream(doc, "wt")) {
        if (os == null) { call.reject("could not open file"); return; }
        os.write(data.getBytes("UTF-8"));
      }
      call.resolve();
    } catch (Exception e) {
      call.reject("write failed: " + e.getMessage());
    }
  }

  @PluginMethod
  public void readBackup(PluginCall call) {
    String uriS = call.getString("uri");
    String name = call.getString("name", "audiobook-backup.json");
    JSObject o = new JSObject();
    try {
      android.content.ContentResolver cr = getContext().getContentResolver();
      Uri doc = uriS == null ? null : findChild(cr, Uri.parse(uriS), name);
      if (doc != null) {
        try (InputStream in = cr.openInputStream(doc)) {
          if (in != null) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            o.put("data", bo.toString("UTF-8"));
          }
        }
      }
      call.resolve(o);
    } catch (Exception e) {
      call.reject("read failed: " + e.getMessage());
    }
  }

  private String nameOf(Uri tree) {
    try {
      Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
      try (Cursor c = getContext().getContentResolver().query(
          doc, new String[]{Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
        if (c != null && c.moveToFirst() && c.getString(0) != null) return c.getString(0);
      }
    } catch (Exception ignored) { }
    return "Folder";
  }

  /** True if the saved permission still exists and the folder can be read (e.g. SD card is inserted). */
  @PluginMethod
  public void hasAccess(PluginCall call) {
    String u = call.getString("uri");
    boolean ok = false;
    if (u != null) {
      for (UriPermission p : getContext().getContentResolver().getPersistedUriPermissions()) {
        if (p.getUri().toString().equals(u) && p.isReadPermission()) ok = true;
      }
      if (ok) ok = canList(Uri.parse(u));
    }
    JSObject o = new JSObject();
    o.put("ok", ok);
    call.resolve(o);
  }

  private boolean canList(Uri tree) {
    try {
      Uri kids = DocumentsContract.buildChildDocumentsUriUsingTree(
          tree, DocumentsContract.getTreeDocumentId(tree));
      try (Cursor c = getContext().getContentResolver().query(
          kids, new String[]{Document.COLUMN_DOCUMENT_ID}, null, null, null)) {
        return c != null;
      }
    } catch (Exception e) {
      return false;
    }
  }

  /** Scans in a background thread and streams results to JS in chunks of 250 files. */
  @PluginMethod
  public void listAudio(final PluginCall call) {
    final String uriS = call.getString("uri");
    final String scanId = call.getString("id", "0");
    final int max = call.getInt("max", 20000);
    if (uriS == null) { call.reject("uri required"); return; }
    cancel = false;
    new Thread(() -> {
      android.content.ContentResolver cr = getContext().getContentResolver();
      Uri tree = Uri.parse(uriS);
      ArrayDeque<String[]> stack = new ArrayDeque<>();
      stack.push(new String[]{DocumentsContract.getTreeDocumentId(tree), ""});
      JSArray items = new JSArray();
      JSArray covers = new JSArray();
      int count = 0;
      int skipped = 0;
      boolean over = false;
      try {
        while (!stack.isEmpty() && !cancel && !over) {
          String[] cur = stack.pop();
          Uri kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, cur[0]);
          try (Cursor c = cr.query(kids, COLS, null, null, null)) {
            if (c == null) { skipped++; continue; }
            while (c.moveToNext()) {
              String id = c.getString(0);
              String name = c.getString(1);
              String mime = c.getString(2);
              if (name == null || name.startsWith(".")) continue;
              String rel = cur[1].isEmpty() ? name : cur[1] + "/" + name;
              if (Document.MIME_TYPE_DIR.equals(mime)) {
                stack.push(new String[]{id, rel});
              } else if (AUDIO.matcher(name).matches()) {
                JSObject o = new JSObject();
                o.put("rel", rel);
                o.put("size", c.getLong(3));
                o.put("mod", c.getLong(4));
                items.put(o);
                count++;
                if (count >= max) { over = true; break; }
              } else if (COVER.matcher(name).matches()) {
                JSObject o = new JSObject();
                o.put("rel", rel);
                covers.put(o);
              }
              if (items.length() >= 250) {
                flush(scanId, items, covers);
                items = new JSArray();
                covers = new JSArray();
              }
            }
          } catch (Exception e) {
            skipped++;
          }
        }
        flush(scanId, items, covers);
        JSObject r = new JSObject();
        r.put("count", count);
        r.put("skipped", skipped);
        r.put("over", over);
        r.put("cancelled", cancel);
        call.resolve(r);
      } catch (Exception e) {
        call.reject("scan failed: " + e.getMessage());
      }
    }, "folder-scan").start();
  }

  private void flush(String scanId, JSArray items, JSArray covers) {
    if (items.length() == 0 && covers.length() == 0) return;
    JSObject o = new JSObject();
    o.put("id", scanId);
    o.put("items", items);
    o.put("covers", covers);
    notifyListeners("chunk", o);
  }

  @PluginMethod
  public void cancelScan(PluginCall call) {
    cancel = true;
    call.resolve();
  }

  @PluginMethod
  public void release(PluginCall call) {
    try {
      getContext().getContentResolver().releasePersistableUriPermission(
          Uri.parse(call.getString("uri")), Intent.FLAG_GRANT_READ_URI_PERMISSION);
    } catch (Exception ignored) { }
    call.resolve();
  }

  /** URL prefix of the private loopback server that streams files (with Range support) to the audio element. */
  @PluginMethod
  public void baseUrl(PluginCall call) {
    try {
      synchronized (this) {
        if (server == null) server = new LocalServer(getContext().getContentResolver());
      }
      JSObject o = new JSObject();
      o.put("base", server.base());
      call.resolve(o);
    } catch (Exception e) {
      call.reject("server: " + e.getMessage());
    }
  }

  @Override
  protected void handleOnDestroy() {
    if (server != null) server.stop();
  }
}
