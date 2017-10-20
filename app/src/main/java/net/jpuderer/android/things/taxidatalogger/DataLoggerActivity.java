/*
 * Copyright 2017 James Puderer
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

package net.jpuderer.android.things.taxidatalogger;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import android.view.WindowManager;

import com.google.android.things.contrib.driver.bmx280.Bmx280SensorDriver;
import com.google.android.things.contrib.driver.gps.NmeaGpsDriver;
import com.google.android.things.pio.PeripheralManagerService;

import net.jpuderer.android.things.driver.hpm.HpmSensorDriver;
import net.jpuderer.android.things.taxidatalogger.cloud.CloudPublisherService;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

// TODO: Use a more flexible logging format (JSON).  I thought I would do some
// querying on the device, but it turns out not.  I should probably just store
// encoded JSON, since that makes it easier to add and remove sensors.
public class DataLoggerActivity extends Activity {
    private static final String TAG = DataLoggerActivity.class.getSimpleName();

    private static final HashSet<Integer> SUPPORTED_SENSORS = new HashSet<Integer>();

    private static final int SAMPLE_INTERVAL_MS = 10000;

    // GPS fixes are considered valid for 10 seconds.  This should be fine, since we should be
    // receiving constant updates, and 10 seconds can be a long time, since we may be moving
    // quickly.
    private static final long GPS_FIX_VALIDITY_MS = 10000;

    private static final String BMX280_I2C_BUS_NAME = "I2C1";
    private static final String HPM_SENSOR_UART_NAME = "UART1";
    private static final String NMEA_GPS_UART_NAME = "USB1-1.4:1.0";

    static {
        SUPPORTED_SENSORS.add(Sensor.TYPE_AMBIENT_TEMPERATURE);
        SUPPORTED_SENSORS.add(Sensor.TYPE_RELATIVE_HUMIDITY);
        SUPPORTED_SENSORS.add(Sensor.TYPE_PRESSURE);
        SUPPORTED_SENSORS.add(Sensor.TYPE_DEVICE_PRIVATE_BASE);
    }

    // BMX280 temperature, humidity, and pressure sensor driver
    Bmx280SensorDriver mBmx280SensorDriver;

    // Honeywell HPM Partical Sensor
    private HpmSensorDriver mHpmDriver;

    // GPS Driver
    NmeaGpsDriver mGpsDriver;

    // Handler for posting runnables to
    Handler mHandler;
    // Token for finding our delayed runnable to perform a sampling
    Object mDoSampleToken = new Object();

    // Database of logging entries
    DatalogDbHelper mDbHelper;
    SQLiteDatabase mDb;

    // Instance of sensor manager
    private SensorManager mSensorManager;

    // Instance of location manager;
    private LocationManager mLocationManager;

    // Record recent sensor values and timestamps for these values
    // If the values are too old when we record data, we return a null
    // value (which is interpreted as data not available).
    private class SensorData {
        Location location;

        float temperature;
        long temperature_timestamp;

        float humidity;
        long humidity_timestamp;

        float pressure;
        long pressure_timestamp;

        public int pm25;
        public int pm10;
        long particle_timestamp;
    };
    public SensorData mSensorData = new SensorData();

    private SensorEventListener mSensorEventListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent sensorEvent) {
            switch (sensorEvent.sensor.getType()) {
                case Sensor.TYPE_AMBIENT_TEMPERATURE:
                    mSensorData.temperature = sensorEvent.values[0];
                    mSensorData.temperature_timestamp = sensorEvent.timestamp;
                    break;
                case Sensor.TYPE_RELATIVE_HUMIDITY:
                    mSensorData.humidity = sensorEvent.values[0];
                    mSensorData.humidity_timestamp = sensorEvent.timestamp;
                    break;
                case Sensor.TYPE_PRESSURE:
                    mSensorData.pressure = sensorEvent.values[0];
                    mSensorData.pressure_timestamp = sensorEvent.timestamp;
                    break;
                case Sensor.TYPE_DEVICE_PRIVATE_BASE:
                     if (HpmSensorDriver.SENSOR_STRING_TYPE.equals(sensorEvent.sensor.getStringType())) {
                        mSensorData.pm25 = (int) sensorEvent.values[0];
                        mSensorData.pm10 = (int) sensorEvent.values[1];
                        mSensorData.particle_timestamp = sensorEvent.timestamp;
                        break;
                    }
                default:
                    Log.w(TAG, "Unexpected sensor type: " + sensorEvent.sensor.getType());
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int i) {

        }
    };

    // Define a listener that responds to location updates
    LocationListener mLocationListener = new LocationListener() {
        public void onLocationChanged(Location location) {
            mSensorData.location = location;
        }

        public void onStatusChanged(String provider, int status, Bundle extras) {}

        public void onProviderEnabled(String provider) {}

        public void onProviderDisabled(String provider) {}
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Devices with a display should not go to sleep
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Create handler
        mHandler = new Handler();

        // Get DB and DB helper
        mDbHelper = new DatalogDbHelper(this);
        mDb = mDbHelper.getWritableDatabase();

        // Acquire a reference to the system Location Manager
        mLocationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

        // Register sensors and start requesting data from them
        registerSensors();

        Log.d(TAG, "Starting data collection...");
        startDataCollection();

        Log.d(TAG, "Start Google Cloud Iot Publisher...");
        Intent intent = new Intent(this, CloudPublisherService.class);
        startService(intent);

        PeripheralManagerService manager = new PeripheralManagerService();
        List<String> deviceList = manager.getUartDeviceList();
        if (deviceList.isEmpty()) {
            Log.i(TAG, "No UART port available on this device.");
        } else {
            Log.i(TAG, "List of available devices: " + deviceList);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopDataCollection();
        unregisterSensors();
    }

    private void registerSensors() {
        mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);

        mSensorManager.registerDynamicSensorCallback(new SensorManager.DynamicSensorCallback() {
            @Override
            public void onDynamicSensorConnected(Sensor sensor) {
                if (SUPPORTED_SENSORS.contains(sensor.getType())) {
                    mSensorManager.registerListener(mSensorEventListener, sensor,
                            SensorManager.SENSOR_DELAY_NORMAL);
                }
            }
        });

        // Register Temperature, Humidity, and Pressure sensor
        try {
            mBmx280SensorDriver = new Bmx280SensorDriver(BMX280_I2C_BUS_NAME);
            mBmx280SensorDriver.registerTemperatureSensor();
            mBmx280SensorDriver.registerHumiditySensor();
            mBmx280SensorDriver.registerPressureSensor();
        } catch (IOException e) {
            Log.e(TAG, "Error registering BMX280 sensor");
        }

        // Register HPM particle sensor driver
        try {
            mHpmDriver = new HpmSensorDriver(HPM_SENSOR_UART_NAME);
            mHpmDriver.registerParticleSensor();
        } catch (IOException e) {
            Log.e(TAG, "Error registering HPM sensor driver");
        }

        // Register GPS driver
        try {
            mGpsDriver = new NmeaGpsDriver(this, NMEA_GPS_UART_NAME,
                9600, 5);
            mGpsDriver.register();

            // Register the listener with the Location Manager to receive location updates
            mLocationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,
                    0, 0, mLocationListener);
        } catch (IOException e) {
            Log.e(TAG, "Error registering GPS driver");
        }
    }

    private void unregisterSensors() {
        mSensorManager.unregisterListener(mSensorEventListener);
        if (mBmx280SensorDriver != null) {
            mBmx280SensorDriver.unregisterTemperatureSensor();
            mBmx280SensorDriver.unregisterHumiditySensor();
            mBmx280SensorDriver.unregisterPressureSensor();
            try {
                mBmx280SensorDriver.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing BMX280 sensor");
            }
        }
        if (mGpsDriver != null) {
            mGpsDriver.unregister();
            try {
                mGpsDriver.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing GPS driver");
            }
        }
        if (mHpmDriver != null) {
            mHpmDriver.unregisterParticleSensor();
            try {
                mHpmDriver.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing GPS driver");
            }
        }
        if (mLocationManager != null)
            mLocationManager.removeUpdates(mLocationListener);
    }

    // Regularly (every SAMPLE_INTERVAL_MS) record sensor values to the database
    private void startDataCollection() {
        final Runnable doDataCollection = new Runnable() {
            private boolean toOld(long timestamp) {
                final long timestamp_ms = TimeUnit.NANOSECONDS.toMillis(timestamp);
                return (SystemClock.uptimeMillis() - timestamp_ms > SAMPLE_INTERVAL_MS);
            }

            @Override
            public void run() {
                // Don't record anything if we don't have a recent GPS fix
                boolean hasGpsFix = false;
                if (mSensorData.location != null) {
                    long delta_ms =
                            TimeUnit.NANOSECONDS.toMillis((SystemClock.elapsedRealtimeNanos() -
                                    mSensorData.location.getElapsedRealtimeNanos()));
                    hasGpsFix = delta_ms < GPS_FIX_VALIDITY_MS;
                }

                // Record a null reading if the sensor data is too old.
                Float temperature = toOld(mSensorData.temperature_timestamp) ?
                        null : mSensorData.temperature;
                Float humidity = toOld(mSensorData.humidity_timestamp) ?
                        null : mSensorData.humidity;
                Float pressure = toOld(mSensorData.pressure_timestamp) ?
                        null : mSensorData.pressure;
                Integer pm25 = toOld(mSensorData.particle_timestamp) ?
                        null : mSensorData.pm25;
                Integer pm10 = toOld(mSensorData.particle_timestamp) ?
                        null : mSensorData.pm10;

                // TODO: Fix time issue in next developer preview.
                //
                // There isn't a *good* way to set the system time in the latest developer
                // preview (DP5.1), if the device is offline:
                //     https://issuetracker.google.com/issues/64426912
                //
                // Since our device may not have network connectivity for extended periods of time
                // (and with possible reboots in between), we need some mechanism of getting the
                // time without network connectivity.  Otherwise the system time will be close to
                // January 1st, 2009 00:00 UTC
                //
                // So, we log the time delivered in the location update (from the GPS), and only
                // log data using these timestamps.  This is fine, since we're only interested in
                // logging data when we have a GPS fix anyway.
                //
                // The semantics of the what the time returned in location updates should be
                // is somewhat confusing.  In practice, we will get a usable time because of the
                // way we're using Location Services with GPS as a source:
                //     https://stackoverflow.com/questions/7017069/gps-time-in-android;

                if (hasGpsFix) {
                    long count = DatalogDbHelper.log(mDb,
                            // Bigtable uses seconds since epoch as a float
                            mSensorData.location.getTime() / 1000d,
                            mSensorData.location.getLatitude(),
                            mSensorData.location.getLongitude(),
                            mSensorData.location.getAccuracy(),
                            mSensorData.location.getAltitude(),
                            mSensorData.location.getVerticalAccuracyMeters(),
                            temperature,
                            humidity,
                            pressure,
                            pm25,
                            pm10);
                    Log.d(TAG, String.format("Logged\n" +
                                    "\tTimestamp: %.6f\n" +
                                    "\tLatitude, Longitude, Accuracy: %.7f, %.7f, %.2fm\n" +
                                    "\tAlititude, Accuracy: %.1fm, %.1fm\n" +
                                    "\tTemperature: %.1f, Humidity: %.1f%%, Pressure: %.1fhPa\n" +
                                    "\tPM2.5, PM10: %d, %d\n" +
                                    "\tRecord Count: %d",
                            mSensorData.location.getTime() / 1000d,
                            mSensorData.location.getLatitude(),
                            mSensorData.location.getLongitude(),
                            mSensorData.location.getAccuracy(),
                            mSensorData.location.getAltitude(),
                            mSensorData.location.getVerticalAccuracyMeters(),
                            temperature,
                            humidity,
                            pressure,
                            pm25,
                            pm10,
                            count));
                } else {
                    Log.d(TAG, "No GPS fix: Not logging data.");
                }
                mHandler.postAtTime(this, mDoSampleToken,
                        SystemClock.uptimeMillis() + SAMPLE_INTERVAL_MS);
            }
        };
        mHandler.postAtTime(doDataCollection, mDoSampleToken,
                SystemClock.uptimeMillis() + SAMPLE_INTERVAL_MS);
    }

    private void stopDataCollection() {
        mHandler.removeCallbacksAndMessages(mDoSampleToken);
    }
}
