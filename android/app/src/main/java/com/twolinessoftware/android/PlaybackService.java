/*
 * Copyright (c) 2011 2linessoftware.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.twolinessoftware.android;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.location.Criteria;
import android.location.LocationManager;
import android.os.AsyncTask;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.twolinessoftware.android.framework.service.comms.gpx.GpxSaxParser;
import com.twolinessoftware.android.framework.service.comms.gpx.GpxSaxParserListener;
import com.twolinessoftware.android.framework.service.comms.gpx.GpxTrackPoint;
import com.twolinessoftware.android.framework.util.Logger;
import com.vividsolutions.jts.geom.Coordinate;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;

import static com.twolinessoftware.android.MainApplication.NOTIFICATION_CHANNEL_ID_DEFAULT;

public class PlaybackService extends Service implements GpxSaxParserListener, SendLocationWorkerQueue.SendLocationWorkerQueueCallback {
    private static final String TAG = PlaybackService.class.getSimpleName();

    private NotificationManagerCompat mNotificationManager;
    private static final int NOTIFICATION_ID = 1;

    private ArrayList<GpxTrackPoint> pointList = new ArrayList<>();
    public static final boolean CONTINUOUS = true;
    public static final int RUNNING = 0;
    public static final int STOPPED = 1;
    public static final int PAUSED = 2;
    public static final int RESUME = 3;
    private static final String PROVIDER_NAME = LocationManager.GPS_PROVIDER;

    private GpxTrackPoint lastPoint;
    private long delayTimeOnReplay = 0;
    private GpxTrackPoint currentPointWorker;

    private final IPlaybackService.Stub mBinder = new IPlaybackService.Stub() {


        @Override
        public void startService(String file) throws RemoteException {
            broadcastStateChange(RUNNING);
            loadGpxFile(file);
            setupTestProvider();
        }

        @Override
        public void stopService() throws RemoteException {
            //mLocationManager.removeTestProvider(PROVIDER_NAME);
            queuePause.reset();
            queue.reset();
            broadcastStateChange(STOPPED);
            cancelExistingTaskIfNecessary();
            onGpsPlaybackStopped();
            stopSelf();
        }

        @Override
        public int getState() throws RemoteException {
            return state;
        }

        @Override
        public void pause() {
            Log.e(TAG, "Pausing Playback Service");
            broadcastStateChange(PAUSED);
            queue.pause();

            if (currentPointWorker != null) {
                Log.d(TAG, "Sending Point at pause  !!!!!!! " + currentPointWorker.getLat() + " - " + currentPointWorker.getLon() + " speed : " + currentPointWorker.getSpeed());
                for (int i = 0; i < QUEUE_PAUSE_SIZE; i++) {
                    SendLocationWorker worker = new SendLocationWorker(mLocationManager, currentPointWorker, PROVIDER_NAME, System.currentTimeMillis());
                    queuePause.addToQueue(worker);
                    if (queuePause.getQueueSize() > 0 && !queuePause.isRunning()) {
                        Log.e(TAG, " Start pause");
                        queuePause.start(delayTimeOnReplay);
                    }
                }
            }
        }

        @Override
        public void resume() {
            Log.e(TAG, "Resuming Playback Service");
            broadcastStateChange(RESUME);
            queuePause.reset();
            queue.resume();
        }

        @Override
        public void updateDelayTime(long timeInMilliseconds) {
            if (state == PAUSED) {
                Log.e(TAG, "Updating Delay Time Playback Service");
                queue.updateDelayTime(timeInMilliseconds);
            }
        }
    };

    private LocationManager mLocationManager;
    private long startTimeOffset;
    private long firstGpsTime;
    private int state;
    private SendLocationWorkerQueue queue, queuePause;
    private final int QUEUE_PAUSE_SIZE = 100;
    private boolean processing;
    private ReadFileTask task;
    private PendingIntent launchIntent, resumeIntent, pauseIntent, stopIntent;
    private final String ACTION_LAUNCH = "Launch";
    private final String ACTION_PAUSE = "Pause";
    private final String ACTION_RESUME = "Resume";
    private final String ACTION_STOP = "Stop";
    private final String STATUS = "Status";

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onCreate() {
        Log.e(TAG, "onCreate Playback Service");
        mNotificationManager = NotificationManagerCompat.from(this);
        mLocationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        queue = new SendLocationWorkerQueue(this);
        queuePause = new SendLocationWorkerQueue(this);
        broadcastStateChange(STOPPED);
        //setupTestProvider();
        processing = false;

        // The PendingIntent to launch our activity if the user selects this notification
        launchIntent = PendingIntent.getActivity(
                this,
                0,
                new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                0);

        resumeIntent = PendingIntent.getService(
                this,
                0,
                new Intent(this, PlaybackService.class)
                        .setAction(ACTION_RESUME)
                        .putExtra(STATUS, PlaybackService.RESUME),
                PendingIntent.FLAG_UPDATE_CURRENT);
        pauseIntent = PendingIntent.getService(
                this,
                0,
                new Intent(this, PlaybackService.class)
                        .setAction(ACTION_PAUSE)
                        .putExtra(STATUS, PlaybackService.PAUSED),
                PendingIntent.FLAG_UPDATE_CURRENT);
        stopIntent = PendingIntent.getService(
                this,
                0,
                new Intent(this, PlaybackService.class)
                        .setAction(ACTION_STOP)
                        .putExtra(STATUS, PlaybackService.STOPPED),
                PendingIntent.FLAG_UPDATE_CURRENT);

        mNotificationManager.cancel(NOTIFICATION_ID);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.e(TAG, "Starting Playback Service");
        if(intent != null && intent.getAction() != null) {
            String action = intent.getAction();
            int status = intent.getIntExtra(STATUS, -1);
            Log.e(TAG, "------------------ " + action + " : " + status);
            if (ACTION_PAUSE.equalsIgnoreCase(action)) {
                try {
                    mBinder.pause();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            } else if (ACTION_RESUME.equalsIgnoreCase(action)) {
                try {
                    mBinder.resume();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            } else if (ACTION_STOP.equalsIgnoreCase(action)) {
                try {
                    mBinder.stopService();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

        String timeFromIntent = null;
        try {
            timeFromIntent = intent.getStringExtra("delayTimeOnReplay");
        } catch (NullPointerException npe) {
            // suppress npe if delay time not available.
            npe.printStackTrace();
        }

        if (timeFromIntent != null && !"".equalsIgnoreCase(timeFromIntent)) {
            delayTimeOnReplay = Long.parseLong(timeFromIntent);
            queue.start(delayTimeOnReplay);
        }

        // We want this service to continue running until it is explicitly
        // stopped, so return sticky.
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy Playback Service");
        super.onDestroy();
    }


    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Log.d(TAG, "onTaskRemoved Playback Service");
        super.onTaskRemoved(rootIntent);
    }

    private void cancelExistingTaskIfNecessary() {
        if (task != null) {
            try {
                task.cancel(true);
            } catch (Exception e) {
                Log.e(TAG, "Unable to cancel playback task. May already be stopped");
            }
        }
    }

    private void loadGpxFile(String file) {
        if (file != null) {
            broadcastStatus(GpsPlaybackBroadcastReceiver.Status.fileLoadStarted);
            cancelExistingTaskIfNecessary();
            task = new ReadFileTask(file);
            task.execute(null, null);

            // In this sample, we'll use the same text for the ticker and the expanded notification
            String text = getString(R.string.push_content_default);
            // Display a notification about us starting.  We put an icon in the status bar.
            showNotification(text);
        }

    }


    private void queueGpxPositions(String xml) {
        GpxSaxParser parser = new GpxSaxParser(this);
        parser.parse(xml);
    }

    private void onGpsPlaybackStopped() {
        broadcastStateChange(STOPPED);
        // Cancel the persistent notification.
        mNotificationManager.cancel(NOTIFICATION_ID);
        disableGpsProvider();
    }

    private void disableGpsProvider() {
        if (mLocationManager.getProvider(PROVIDER_NAME) != null) {
            mLocationManager.setTestProviderEnabled(PROVIDER_NAME, false);
            mLocationManager.clearTestProviderEnabled(PROVIDER_NAME);
            mLocationManager.clearTestProviderLocation(PROVIDER_NAME);
            mLocationManager.removeTestProvider(PROVIDER_NAME);
        }
    }

    private void setupTestProvider() {
        mLocationManager.addTestProvider(PROVIDER_NAME, false, //requiresNetwork,
                false, // requiresSatellite,
                false, // requiresCell,
                false, // hasMonetaryCost,
                false, // supportsAltitude,
                false, // supportsSpeed, s
                false, // upportsBearing,
                Criteria.POWER_LOW, // powerRequirement
                Criteria.ACCURACY_FINE); // accuracy

        mLocationManager.setTestProviderEnabled("gps", true);
    }


    /**
     * Show a notification while this service is running.
     */
    private void showNotification(String contentMessage) {
        final Notification notification;
        if (state == PAUSED) {
            notification = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID_DEFAULT)
                    // Show controls on lock screen even when user hides sensitive content.
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setSmallIcon(R.drawable.ic_playback_location)
                    .setContentText(contentMessage)
                    .setWhen(System.currentTimeMillis())
                    //.setContentIntent(contentIntent)
                    .setContentTitle(getString(R.string.chanel_description))
                    /*.setStyle(new NotificationCompat.InboxStyle()
                            .addLine("Much longer text that cannot fit one line...")
                            .addLine("Much longer text that cannot fit one line..."))*/
                    // Add media control buttons that invoke intents in your media service
                    .addAction(R.drawable.ic_play, getString(R.string.push_action_title_resume), resumeIntent) // #0
                    .addAction(R.drawable.ic_stop, getString(R.string.push_action_title_stop), stopIntent)  // #1
                    .addAction(R.drawable.ic_launch, getString(R.string.push_action_title_launch), launchIntent)  // #2
                    .setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
                            .setShowActionsInCompactView(0, 1, 2))
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .build();
        } else {
            notification = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID_DEFAULT)
                    // Show controls on lock screen even when user hides sensitive content.
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setSmallIcon(R.drawable.ic_playback_location)
                    .setContentText(contentMessage)
                    .setWhen(System.currentTimeMillis())
                    //.setContentIntent(contentIntent)
                    .setContentTitle(getString(R.string.chanel_description))
                    /*.setStyle(new NotificationCompat.InboxStyle()
                            .addLine("Much longer text that cannot fit one line...")
                            .addLine("Much longer text that cannot fit one line..."))*/
                    // Add media control buttons that invoke intents in your media service
                    .addAction(R.drawable.ic_pause, getString(R.string.push_action_title_pause), pauseIntent)  // #0
                    .addAction(R.drawable.ic_stop, getString(R.string.push_action_title_stop), stopIntent)  // #1
                    .addAction(R.drawable.ic_launch, getString(R.string.push_action_title_launch), launchIntent)  // #2
                    .setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
                            .setShowActionsInCompactView(0, 1, 2))
                    .setColor(ContextCompat.getColor(this, R.color.colorPrimary))
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .build();
        }

        // Send the notification.
        mNotificationManager.notify(NOTIFICATION_ID, notification);
    }

    private String loadFile(String file) {
        try {
            File f = new File(file);
            FileInputStream fileIS = new FileInputStream(f);
            BufferedReader buf = new BufferedReader(new InputStreamReader(fileIS));
            String readString = new String();
            StringBuffer xml = new StringBuffer();
            while ((readString = buf.readLine()) != null) {
                xml.append(readString);
            }
            Logger.d(TAG, "Finished reading in file");
            return xml.toString();
        } catch (Exception e) {
            broadcastError("Error in the GPX file, unable to read it");
        }
        return null;
    }


    @Override
    public void onGpxError(String message) {
        broadcastError(message);
    }


    @Override
    public void onGpxPoint(GpxTrackPoint item) {
        long delay = System.currentTimeMillis() + 2000; // ms until the point should be displayed

        long gpsPointTime = 0;

        // Calculate the delay
        if (item.getTime() != null) {
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");

            try {
                Date gpsDate = format.parse(item.getTime());
                gpsPointTime = gpsDate.getTime();
            } catch (ParseException e) {
                Log.e(TAG, "Unable to parse time:" + item.getTime());
            }

            if (firstGpsTime == 0)
                firstGpsTime = gpsPointTime;

            if (startTimeOffset == 0)
                startTimeOffset = System.currentTimeMillis();

            delay = (gpsPointTime - firstGpsTime) + startTimeOffset;
        }

        if (lastPoint != null) {
            item.setHeading(calculateHeadingFromPreviousPoint(lastPoint, item));
            item.setSpeed(calculateSpeedFromPreviousPoint(lastPoint, item));
        } else {
            item.setHeading(0.0);
            item.setSpeed(15.0);
        }

        lastPoint = item;

        pointList.add(item);
        if (state == RUNNING) {
            if (delay > 0) {
                Log.d(TAG, "Sending Point in:" + (delay - System.currentTimeMillis()) + "ms");
                SendLocationWorker worker = new SendLocationWorker(mLocationManager, item, PROVIDER_NAME, delay);
                queue.addToQueue(worker);
            } else {
                Log.e(TAG, "Invalid Time at Point:" + gpsPointTime + " delay from current time:" + delay);
            }
        }

    }

    private double calculateHeadingFromPreviousPoint(GpxTrackPoint currentPoint, GpxTrackPoint lastPoint) {

        double angleBetweenPoints = Math.atan2((lastPoint.getLon() - currentPoint.getLon()), (lastPoint.getLat() - currentPoint.getLat()));
        return Math.toDegrees(angleBetweenPoints);
    }

    private double calculateSpeedFromPreviousPoint(GpxTrackPoint currentPoint, GpxTrackPoint lastPoint) {

        Coordinate startCoordinate = new Coordinate(lastPoint.getLon(), lastPoint.getLat());
        Coordinate endCoordinate = new Coordinate(currentPoint.getLon(), currentPoint.getLat());
        double distance = startCoordinate.distance(endCoordinate) * 100000;
        return distance;

    }

    @Override
    public void onGpxStart() {
        // Start Parsing
    }

    @Override
    public void onGpxEnd() {
        // End Parsing
    }

    private void broadcastStatus(GpsPlaybackBroadcastReceiver.Status status) {
        Intent i = new Intent(GpsPlaybackBroadcastReceiver.INTENT_BROADCAST);
        i.putExtra(GpsPlaybackBroadcastReceiver.INTENT_STATUS, status.toString());
        sendBroadcast(i);
    }

    private void broadcastError(String message) {
        Intent i = new Intent(GpsPlaybackBroadcastReceiver.INTENT_BROADCAST);
        i.putExtra(GpsPlaybackBroadcastReceiver.INTENT_STATUS, GpsPlaybackBroadcastReceiver.Status.fileError.toString());
        i.putExtra(GpsPlaybackBroadcastReceiver.INTENT_STATE, state);
        sendBroadcast(i);
    }

    private void broadcastStateChange(int newState) {
        state = newState;
        Intent i = new Intent(GpsPlaybackBroadcastReceiver.INTENT_BROADCAST);
        i.putExtra(GpsPlaybackBroadcastReceiver.INTENT_STATUS, GpsPlaybackBroadcastReceiver.Status.statusChange.toString());
        i.putExtra(GpsPlaybackBroadcastReceiver.INTENT_STATE, state);
        sendBroadcast(i);
    }

    private class ReadFileTask extends AsyncTask<Void, Integer, Void> {

        private String file;

        public ReadFileTask(String file) {
            super();
            this.file = file;
        }

        @Override
        protected void onPostExecute(Void result) {
            broadcastStatus(GpsPlaybackBroadcastReceiver.Status.fileLoadfinished);
        }

        @Override
        protected Void doInBackground(Void... arg0) {
            // Reset the existing values
            firstGpsTime = 0;
            startTimeOffset = 0;

            String xml = loadFile(file);
            publishProgress(1);
            queueGpxPositions(xml);
            return null;
        }

        @Override
        protected void onProgressUpdate(Integer... progress) {
            switch (progress[0]) {
                case 1:
                    broadcastStatus(GpsPlaybackBroadcastReceiver.Status.fileLoadfinished);
                    break;
            }
        }

    }

    @Override
    public void onSendLocation(GpxTrackPoint point) {
        currentPointWorker = point;
        String status = "";
        switch (state) {
            case RUNNING:
            case RESUME:
                status = "RUNNING";
                break;
            case PAUSED:
                status = "PAUSED";
                currentPointWorker.setSpeed(0.0);
                break;
            case STOPPED:
                status = "STOPPED";
                break;
        }
        String contentMessage = String.format(getString(R.string.push_content_with_status), status, String.format("%.02f", point.getSpeed()), point.getLat(), point.getLon());
        showNotification(contentMessage);
    }

    @Override
    public void onEndSendLocation() {
        Log.e(TAG, "onEndSendLocation");
        if (state != STOPPED) {
            // Stop at last point in gpx (speed to zero) before stop service.
            currentPointWorker.setSpeed(0.0);
            SendLocationWorker worker = new SendLocationWorker(mLocationManager, currentPointWorker, PROVIDER_NAME, System.currentTimeMillis());
            worker.run();

            try {
                mBinder.stopService();
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }
}
