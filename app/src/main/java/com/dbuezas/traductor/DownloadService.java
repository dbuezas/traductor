package com.dbuezas.traductor;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.os.PowerManager;

import java.util.List;

/**
 * Keeps model downloads going when the app is in the background or the screen is off: a
 * foreground service with a progress notification, plus CPU and Wi-Fi locks. It stops by
 * itself when no download is left.
 */
public class DownloadService extends Service {
    private static final String CHANNEL = "downloads";
    private static final int ID = 1;
    private PowerManager.WakeLock cpu;
    private WifiManager.WifiLock wifi;
    private long lastUpdate;

    @Override
    public void onCreate() {
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, getString(R.string.downloads_channel), NotificationManager.IMPORTANCE_LOW));
        startForeground(ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        cpu = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "traductor:download");
        cpu.acquire(6 * 60 * 60 * 1000L);
        wifi = ((WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE))
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "traductor:download");
        wifi.acquire();
        Models.serviceListener = (m, done) -> {
            long now = System.currentTimeMillis();
            if (!done && now - lastUpdate < 1000) return;   // at most once a second
            lastUpdate = now;
            if (Models.downloading().isEmpty()) stopSelf();
            else getSystemService(NotificationManager.class).notify(ID, notification());
        };
        if (Models.downloading().isEmpty()) stopSelf();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        return START_NOT_STICKY;
    }

    /** "Downloading 2 models · 1.2 of 3.4 GB" with a progress bar; tapping it opens the settings. */
    private Notification notification() {
        List<Models.Model> list = Models.downloading();
        long total = 0, got = 0;
        for (Models.Model m : list) {
            total += m.size;
            Long p = Models.progress.get(m.id);
            got += p == null ? 0 : p;
        }
        String title = list.size() == 1 ? list.get(0).label : getString(R.string.downloads_title, list.size());
        String text = getString(R.string.bytes_of, String.format("%.1f GB", got / 1e9), String.format("%.1f GB", total / 1e9));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, ModelsActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentText(text)
                .setProgress(1000, total == 0 ? 0 : (int) (got * 1000 / total), total == 0)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .build();
    }

    @Override
    public void onDestroy() {
        Models.serviceListener = null;
        if (cpu.isHeld()) cpu.release();
        if (wifi.isHeld()) wifi.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
