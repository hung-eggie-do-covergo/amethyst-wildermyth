package net.kdt.pojavlaunch.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import net.kdt.pojavlaunch.R;

/**
 * Keeps a game download or copy alive with the screen off: a foreground notification so Android keeps the
 * process, plus CPU and Wi-Fi locks so it keeps working. The transfer itself runs in WildermythActivity.
 */
public class WildermythTransferService extends Service {
    private static final String CHANNEL = "wildermyth_transfer";
    private static final int ID = 7631;
    private PowerManager.WakeLock cpu;
    private WifiManager.WifiLock wifi;

    public static void start(Context ctx) {
        ContextCompat.startForegroundService(ctx, new Intent(ctx, WildermythTransferService.class));
    }

    public static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, WildermythTransferService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Game download", NotificationManager.IMPORTANCE_LOW));
        Notification n = new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.wm_icon_foreground)
                .setContentTitle("Wildermyth")
                .setContentText("Downloading game files")
                .setProgress(0, 0, true)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(ID, n);
        if (cpu == null) {
            cpu = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wildermyth:transfer");
            cpu.acquire(3 * 60 * 60 * 1000L); // a stuck transfer must not hold the device awake forever
            wifi = ((WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE))
                    .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "wildermyth:transfer");
            wifi.acquire();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        if (cpu != null && cpu.isHeld()) cpu.release();
        if (wifi != null && wifi.isHeld()) wifi.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
