/*
 * Copyright 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.jpuderer.android.things.taxidatalogger.cloud;

import android.app.Service;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.provider.BaseColumns;
import android.support.annotation.Nullable;
import android.support.annotation.WorkerThread;
import android.util.Log;

import net.jpuderer.android.things.taxidatalogger.DatalogDbHelper;
import net.jpuderer.android.things.taxidatalogger.cloud.cloudiot.CloudIotOptions;
import net.jpuderer.android.things.taxidatalogger.cloud.cloudiot.MQTTPublisher;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Handle asynchronous cloud sensor logging requests via a Binder interface. Sensor events are
 * periodically published to the cloud via a {@link CloudPublisher}.
 * <p>
 */
public class CloudPublisherService extends Service {
    private static final String TAG = "CloudPublisherService";

    private static final String INTENT_CONFIGURE_ACTION =
            "net.jpuderer.android.things.taxidatalogger.CONFIGURE";
    private static final String INTENT_CLEAR_DATA_ACTION =
            "net.jpuderer.android.things.taxidatalogger.CLEAR_DATA";
    private static final String INTENT_RESEND_DATA_ACTION =
            "net.jpuderer.android.things.taxidatalogger.RESEND_DATA";

    private static final String CONFIG_SHARED_PREFERENCES_KEY = "cloud_iot_config";

    private static final long PUBLISH_INTERVAL_MS = TimeUnit.SECONDS.toMillis(20);

    // After this amount of tentatives, the publish interval will change from PUBLISH_INTERVAL_MS
    // to BACKOFF_INTERVAL_MS until a successful connection has been established.
    private static final long ERRORS_TO_INITIATE_BACKOFF = 20;
    private static final long BACKOFF_INTERVAL_MS = TimeUnit.MINUTES.toMillis(1);

    // Database of logging entries
    DatalogDbHelper mDbHelper;
    SQLiteDatabase mDatabase;

    private Looper mServiceLooper;
    private Handler mServiceHandler;
    private CloudPublisher mPublisher;

    private AtomicInteger mUnsuccessfulTentatives = new AtomicInteger(0);

    private String mDeviceId;

    // Runnable to periodically attempt to publish data to the cloud.
    private final Runnable mSensorConsumerRunnable = new Runnable() {
        @Override
        public void run() {
            long delayForNextTentative = PUBLISH_INTERVAL_MS;
            try {
                initializeIfNeeded();
                processCollectedSensorData();
                mUnsuccessfulTentatives.set(0);
            } catch (Throwable t) {
                if (mUnsuccessfulTentatives.get() >= ERRORS_TO_INITIATE_BACKOFF) {
                    delayForNextTentative = BACKOFF_INTERVAL_MS;
                } else {
                    mUnsuccessfulTentatives.incrementAndGet();
                }
                Log.e(TAG, String.format(Locale.getDefault(),
                        "Cannot publish. %d unsuccessful tentatives, will try again in %d ms",
                        mUnsuccessfulTentatives.get(), delayForNextTentative), t);
            } finally {
                mServiceHandler.postDelayed(this, delayForNextTentative);
            }
        }
    };

    @WorkerThread
    void processCollectedSensorData() throws JSONException {
        if (mPublisher == null || !mPublisher.isReady()) {
            return;
        }

        // Query the data we wish to push to the cloud
        long lastSyndId = DatalogDbHelper.getLastSyncId(this);
        Cursor cursor = mDatabase.query(DatalogDbHelper.TABLE_NAME,
                null,
                DatalogDbHelper.DatalogEntry._ID + " > " + lastSyndId,
                null,
                null,
                null,
                BaseColumns._ID + " ASC");
        Log.i(TAG, "publishing " + cursor.getCount() + " log entries");

        final int columnCount = cursor.getColumnCount();
        for (cursor.moveToFirst(); !cursor.isAfterLast(); cursor.moveToNext()) {
            JSONObject entryObject = new JSONObject();
            // Add the device ID to the JSON record.
            //
            // FIXME: There should be a way to get Google Cloud IOT to do this on its
            // end, so I don't need to trust the IDs.
            entryObject.put("deviceId", mDeviceId);
            long id = 0;
            for (int i = 0; i < columnCount; i++) {
                String columnName = cursor.getColumnName(i);
                if (columnName != null) {
                    // Note the most recent ID we've transmitted, but don't include
                    // the ID column in the JSON message
                    if (columnName.equals(BaseColumns._ID)) {
                        id = cursor.getLong(i);
                        continue;
                    }
                    if (cursor.getType(i) == Cursor.FIELD_TYPE_FLOAT) {
                        entryObject.put(columnName, cursor.getDouble(i));
                    } else {
                        entryObject.put(columnName, cursor.getString(i));
                    }
                }
            }
            // Push message to the cloud
            mPublisher.publish(entryObject.toString());
            Log.d(TAG, "Sent entry: " + (cursor.getPosition()+1) + "/" +
                    cursor.getCount());
            if (id > lastSyndId)
                DatalogDbHelper.setLastSyncId(this, id);
        }
        cursor.close();
    }

    private CloudIotOptions readOptions(Intent intent) {
        CloudIotOptions options = CloudIotOptions.from(
                getSharedPreferences(CONFIG_SHARED_PREFERENCES_KEY, MODE_PRIVATE));
        if (intent != null) {
            options = CloudIotOptions.reconfigure(options, intent.getExtras());
        }
        return options;
    }

    private void saveOptions(CloudIotOptions options) {
        options.saveToPreferences(getSharedPreferences(
                CONFIG_SHARED_PREFERENCES_KEY, MODE_PRIVATE));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // Get DB and DB helper
        mDbHelper = new DatalogDbHelper(this);
        mDatabase = mDbHelper.getWritableDatabase();

        initializeIfNeeded();
        HandlerThread thread = new HandlerThread("CloudPublisherService");
        thread.start();
        mServiceLooper = thread.getLooper();
        mServiceHandler = new Handler(mServiceLooper);
        mServiceHandler.postDelayed(mSensorConsumerRunnable, PUBLISH_INTERVAL_MS);
    }

    private void initializeIfNeeded() {
        if (mPublisher == null) {
            try {
                final CloudIotOptions options = readOptions(null);
                mPublisher = new MQTTPublisher(options);
                mDeviceId = options.getDeviceId();
            } catch (Throwable t) {
                Log.e(TAG, "Could not create MQTTPublisher. Will try again later", t);
            }
        }
    }

    @Override
    @Nullable
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null)
            return START_STICKY;
        final String action = intent.getAction();
        if (INTENT_CONFIGURE_ACTION.equals(action)) {
            Log.i(TAG, "Configuring publisher with intent.");
            CloudIotOptions options = readOptions(intent);
            saveOptions(options);
            if (mPublisher != null) {
                mPublisher.reconfigure(options);
            }
        } else if (INTENT_CLEAR_DATA_ACTION.equals(action)) {
            // Clear all log entries from the table
            DatalogDbHelper.clearEntries(mDatabase);
        } else if (INTENT_RESEND_DATA_ACTION.equals(action)) {
            // Reset the lastSyncId to resend all of the data
            DatalogDbHelper.setLastSyncId(this, -1);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        mServiceLooper.quit();
        mServiceLooper = null;
    }
}
