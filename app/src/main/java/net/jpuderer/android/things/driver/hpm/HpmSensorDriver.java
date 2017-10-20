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


package net.jpuderer.android.things.driver.hpm;

import android.hardware.Sensor;
import android.os.Handler;
import android.os.Looper;

import com.google.android.things.userdriver.UserDriverManager;
import com.google.android.things.userdriver.UserSensor;
import com.google.android.things.userdriver.UserSensorDriver;
import com.google.android.things.userdriver.UserSensorReading;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class HpmSensorDriver implements AutoCloseable {
    private static final String TAG = "HpmSensorDriver";

    // DRIVER parameters
    // documented at https://source.android.com/devices/sensors/hal-interface.html#sensor_t
    private static final String DRIVER_VENDOR = "Honeywell";
    private static final String DRIVER_NAME = "HPM particle sensor";
    // Sensor makes measurements once every second
    private static final int DRIVER_MIN_DELAY_US = (int) HpmSensor.HPM_MEASUREMENT_INTERVAL;
    // The maximum value seems pretty arbitrary, so we just say every ten seconds,
    private static final int DRIVER_MAX_DELAY_US = (int) TimeUnit.SECONDS.toMicros(10);

    public static final String SENSOR_STRING_TYPE = "net.jpuderer.android.things.driver.hpm";

    private HpmSensor mDevice;
    private ParticleCountUserDriver mUserDriver;

    Handler mHandler;

    /**
     * Create a new HPM sensor driver connected to the given UART.
     * The driver emits {@link Sensor} with PM2.5 and PM10 particle
     * could when registered.
     *
     * @param uartDevice Name of UART device the sensor is connected to.
     * @throws IOException Sensor error
     * @see #registerParticleSensor()
     */
    public HpmSensorDriver(String uartDevice) throws IOException {
        this(uartDevice, null);
    }

    /**
     * Create a new HPM sensor driver connected to the given UART.
     * The driver emits {@link Sensor} with PM2.5 and PM10 particle
     * could when registered.
     *
     * @param uartDevice Name of UART device the sensor is connected to.
     * @param handler Name of UART device the sensor is connected to.
     * @throws IOException Sensor error
     * @see #registerParticleSensor()
     */
    public HpmSensorDriver(String uartDevice, Handler handler) throws IOException {
        mHandler = new Handler(handler == null ? Looper.myLooper() : handler.getLooper());
        mDevice = new HpmSensor(uartDevice, mHandler);
    }

    /**
     * Close the driver and the underlying device.
     * @throws IOException
     */
    @Override
    public void close() throws IOException {
        unregisterParticleSensor();
        if (mDevice != null) {
            try {
                mDevice.stop();
            } finally {
                mDevice = null;
            }
        }
    }

    /**
     * Register a {@link UserSensor} that pipes particle count readings into the
     * Android SensorManager.
     * @see #unregisterParticleSensor()
     */
    public void registerParticleSensor() {
        if (mDevice == null) {
            throw new IllegalStateException("cannot register closed driver");
        }

        if (mUserDriver == null) {
            mUserDriver = new ParticleCountUserDriver();
            UserDriverManager.getManager().registerSensor(mUserDriver.getUserSensor());
        }
    }

    /**
     * Unregister the particle sensor {@link UserSensor}.
     */
    public void unregisterParticleSensor() {
        if (mUserDriver != null) {
            UserDriverManager.getManager().unregisterSensor(mUserDriver.getUserSensor());
            mUserDriver = null;
        }
    }

    private void maybeStop() throws IOException {
        if (mUserDriver == null || !mUserDriver.isEnabled()) {
            mDevice.stop();
        } else {
            mDevice.start();
        }
    }

    private class ParticleCountUserDriver extends UserSensorDriver {
        private static final int DRIVER_VERSION = 1;
        private static final String DRIVER_REQUIRED_PERMISSION = "";

        private boolean mEnabled;
        private UserSensor mUserSensor;

        private UserSensor getUserSensor() {
            if (mUserSensor == null) {
                mUserSensor = new UserSensor.Builder()
                        .setCustomType(Sensor.TYPE_DEVICE_PRIVATE_BASE,
                                SENSOR_STRING_TYPE,
                                Sensor.REPORTING_MODE_CONTINUOUS)
                        .setName(DRIVER_NAME)
                        .setVendor(DRIVER_VENDOR)
                        .setVersion(DRIVER_VERSION)
                        .setMaxRange(HpmSensor.HPM_PARTICLE_MAX)
                        .setResolution(HpmSensor.HPM_PARTICLE_RESOLUTION)
                        .setPower(HpmSensor.HPM_POWER_CONSUMPTION_UA)
                        .setMinDelay(DRIVER_MIN_DELAY_US)
                        .setRequiredPermission(DRIVER_REQUIRED_PERMISSION)
                        .setMaxDelay(DRIVER_MAX_DELAY_US)
                        .setUuid(UUID.randomUUID())
                        .setDriver(this)
                        .build();
            }
            return mUserSensor;
        }

        @Override
        public UserSensorReading read() throws IOException {
            return new UserSensorReading(new float[]{mDevice.readPm25(), mDevice.readPm10()});
        }

        @Override
        public void setEnabled(boolean enabled) throws IOException {
            mEnabled = enabled;
            maybeStop();
        }

        private boolean isEnabled() {
            return mEnabled;
        }
    }
}
