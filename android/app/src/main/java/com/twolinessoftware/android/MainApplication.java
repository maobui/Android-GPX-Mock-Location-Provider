package com.twolinessoftware.android;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;
import android.os.StrictMode;


public class MainApplication extends Application {
    public static final String NOTIFICATION_CHANNEL_ID_DEFAULT = "default";

    @Override
    public void onCreate() {
        super.onCreate();

        /*
        Never
        Never ever
        Never ever ever
        Never ever ever EVER EVERERER do this in production code.

        This is to get around android.os.FileUriExposedException in>=API24
        The correct solution is to use a content:// URI with a FileProvider.

         However since this app is only used for test purposes I'm using this work-around
        */
        StrictMode.VmPolicy.Builder builder = new StrictMode.VmPolicy.Builder();
        StrictMode.setVmPolicy(builder.build());

        createNotificationChannel();
    }



    private void createNotificationChannel() {
        // Create the NotificationChannel, but only on API 26+ because
        // the NotificationChannel class is new and not in the support library
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = getString(R.string.chanel_name);
            String description = getString(R.string.chanel_description);
            int importance = NotificationManager.IMPORTANCE_LOW;
            NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL_ID_DEFAULT, name, importance);
            channel.setDescription(description);
            // Register the channel with the system; you can't change the importance
            // or other notification behaviors after this
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }
    }
}
