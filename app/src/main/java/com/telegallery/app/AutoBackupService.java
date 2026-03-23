package com.telegallery.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class AutoBackupService extends Service {

    private static final String TAG = "AutoBackupService";
    private static final String CHANNEL_ID = "tg_backup";
    private static final int NOTIF_ID = 1001;

    private ContentObserver mediaObserver;
    private ExecutorService executor;
    private OkHttpClient httpClient;
    private BackupPrefs prefs;

    @Override
    public void onCreate() {
        super.onCreate();
        executor = Executors.newSingleThreadExecutor();
        httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build();
        prefs = new BackupPrefs(this);
        createNotifChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "INITIAL_BACKUP".equals(intent.getAction())) {
            startForeground(NOTIF_ID, buildNotif("Auto Backup চলছে…", 0, 0));
            executor.execute(this::doInitialBackup);
        } else {
            startForeground(NOTIF_ID, buildNotif("Auto Backup চালু আছে", 0, 0));
            startWatching();
        }
        return START_STICKY; // restart if killed
    }

    /* ── Watch MediaStore for new images ── */
    private void startWatching() {
        if (mediaObserver != null) return;
        mediaObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                executor.execute(() -> checkNewPhoto(uri));
            }
        };
        getContentResolver().registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true, mediaObserver);
        Log.d(TAG, "Watching MediaStore");
    }

    private void checkNewPhoto(Uri uri) {
        if (!prefs.isBackupEnabled()) return;
        String token = prefs.getToken();
        String chatId = prefs.getChatId();
        if (token.isEmpty() || chatId.isEmpty()) return;

        // Get latest image added after last backup time
        long lastBackup = prefs.getLastBackupTime();
        String[] proj = {
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME
        };
        String sel = MediaStore.Images.Media.DATE_ADDED + " > ?";
        String[] args = { String.valueOf(lastBackup / 1000) };
        String order = MediaStore.Images.Media.DATE_ADDED + " DESC";

        try (Cursor c = getContentResolver().query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, sel, args, order)) {
            if (c == null || !c.moveToFirst()) return;
            do {
                long id = c.getLong(c.getColumnIndexOrThrow(MediaStore.Images.Media._ID));
                String name = c.getString(c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME));
                String bucket = c.getString(c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME));

                Uri imgUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
                String caption = "#TGG|a:" + (bucket != null ? bucket : "All") + "|c:Auto Backup";

                boolean ok = uploadToTelegram(imgUri, name, caption, token, chatId);
                if (ok) {
                    prefs.setLastBackupTime(System.currentTimeMillis());
                    updateNotif("Backup: " + name, 0, 0);
                    broadcastUploaded(imgUri.toString(), bucket != null ? bucket : "All");
                }
            } while (c.moveToNext());
        } catch (Exception e) {
            Log.e(TAG, "checkNewPhoto error", e);
        }
    }

    /* ── Initial backup of ALL existing photos ── */
    private void doInitialBackup() {
        String token = prefs.getToken();
        String chatId = prefs.getChatId();
        if (token.isEmpty() || chatId.isEmpty()) { stopSelf(); return; }

        String[] proj = {
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED
        };
        String order = MediaStore.Images.Media.DATE_ADDED + " ASC";

        try (Cursor c = getContentResolver().query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, null, null, order)) {
            if (c == null) { stopSelf(); return; }
            int total = c.getCount();
            int done = 0;
            broadcastProgress(done, total, "শুরু হচ্ছে…");

            while (c.moveToNext()) {
                if (!prefs.isBackupEnabled()) break;
                long id = c.getLong(c.getColumnIndexOrThrow(MediaStore.Images.Media._ID));
                String name = c.getString(c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME));
                String bucket = c.getString(c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME));

                // Skip already backed up
                if (prefs.isAlreadyBackedUp(id)) { done++; continue; }

                Uri imgUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
                String caption = "#TGG|a:" + (bucket != null ? bucket : "All") + "|c:Auto Backup";

                boolean ok = uploadToTelegram(imgUri, name, caption, token, chatId);
                if (ok) {
                    prefs.markBackedUp(id);
                    broadcastUploaded(imgUri.toString(), bucket != null ? bucket : "All");
                }
                done++;
                updateNotif("Backup: " + done + "/" + total, done, total);
                broadcastProgress(done, total, name);
            }
            prefs.setLastBackupTime(System.currentTimeMillis());
            broadcastProgress(total, total, "সম্পন্ন");
            updateNotif("Backup সম্পন্ন — " + total + " ছবি", 0, 0);
        } catch (Exception e) {
            Log.e(TAG, "Initial backup error", e);
        }

        // After initial backup, start watching
        startWatching();
    }

    /* ── Upload one image to Telegram ── */
    private boolean uploadToTelegram(Uri uri, String filename, String caption, String token, String chatId) {
        File tmpFile = null;
        try {
            tmpFile = copyToTemp(uri, filename);
            if (tmpFile == null) return false;

            RequestBody fileBody = RequestBody.create(tmpFile, MediaType.parse("image/jpeg"));
            MultipartBody.Builder mb = new MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", chatId)
                .addFormDataPart("photo", filename, fileBody);
            if (caption != null && !caption.isEmpty())
                mb.addFormDataPart("caption", caption);

            Request req = new Request.Builder()
                .url("https://api.telegram.org/bot" + token + "/sendPhoto")
                .post(mb.build()).build();

            try (Response resp = httpClient.newCall(req).execute()) {
                return resp.isSuccessful();
            }
        } catch (Exception e) {
            Log.e(TAG, "Upload error: " + e.getMessage());
            return false;
        } finally {
            if (tmpFile != null) tmpFile.delete();
        }
    }

    private File copyToTemp(Uri uri, String name) {
        try {
            File tmp = new File(getCacheDir(), "backup_" + System.currentTimeMillis() + ".jpg");
            try (InputStream in = getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(tmp)) {
                if (in == null) return null;
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            return tmp;
        } catch (IOException e) { return null; }
    }

    /* ── Broadcast to WebView (JS) ── */
    private void broadcastUploaded(String url, String album) {
        Intent i = new Intent("com.telegallery.PHOTO_UPLOADED");
        i.putExtra("url", url); i.putExtra("album", album);
        sendBroadcast(i);
    }

    private void broadcastProgress(int done, int total, String current) {
        Intent i = new Intent("com.telegallery.BACKUP_PROGRESS");
        i.putExtra("done", done); i.putExtra("total", total); i.putExtra("current", current);
        sendBroadcast(i);
    }

    /* ── Notification ── */
    private void createNotifChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "TeleGallery Backup", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Auto backup to Telegram");
            ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        }
    }

    private Notification buildNotif(String text, int progress, int max) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentTitle("TeleGallery")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW);
        if (max > 0) b.setProgress(max, progress, false);
        return b.build();
    }

    private void updateNotif(String text, int progress, int max) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(NOTIF_ID, buildNotif(text, progress, max));
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        if (mediaObserver != null) {
            getContentResolver().unregisterContentObserver(mediaObserver);
        }
        if (executor != null) executor.shutdownNow();
        super.onDestroy();
    }
}
