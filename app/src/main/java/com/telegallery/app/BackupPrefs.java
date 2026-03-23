package com.telegallery.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

public class BackupPrefs {

    private static final String PREF_NAME  = "tg_backup_prefs";
    private static final String KEY_TOKEN  = "token";
    private static final String KEY_CHAT   = "chat_id";
    private static final String KEY_ENABLED = "backup_enabled";
    private static final String KEY_LAST_TIME = "last_backup_time";
    private static final String KEY_BACKED_UP = "backed_up_ids";

    private final SharedPreferences sp;

    public BackupPrefs(Context ctx) {
        sp = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public void setCredentials(String token, String chatId) {
        sp.edit().putString(KEY_TOKEN, token).putString(KEY_CHAT, chatId).apply();
    }

    public String getToken()  { return sp.getString(KEY_TOKEN, ""); }
    public String getChatId() { return sp.getString(KEY_CHAT, ""); }

    public void setBackupEnabled(boolean enabled) {
        sp.edit().putBoolean(KEY_ENABLED, enabled).apply();
    }
    public boolean isBackupEnabled() { return sp.getBoolean(KEY_ENABLED, false); }

    public void setLastBackupTime(long ms) {
        sp.edit().putLong(KEY_LAST_TIME, ms).apply();
    }
    public long getLastBackupTime() { return sp.getLong(KEY_LAST_TIME, 0L); }

    public void markBackedUp(long imageId) {
        Set<String> ids = new HashSet<>(sp.getStringSet(KEY_BACKED_UP, new HashSet<>()));
        ids.add(String.valueOf(imageId));
        sp.edit().putStringSet(KEY_BACKED_UP, ids).apply();
    }

    public boolean isAlreadyBackedUp(long imageId) {
        Set<String> ids = sp.getStringSet(KEY_BACKED_UP, new HashSet<>());
        return ids.contains(String.valueOf(imageId));
    }

    public void clearBackedUpIds() {
        sp.edit().remove(KEY_BACKED_UP).apply();
    }
}
