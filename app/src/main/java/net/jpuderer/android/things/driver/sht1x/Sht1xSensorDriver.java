package net.jpuderer.android.things.driver.sht1x;

import android.hardware.Sensor;

import com.google.android.things.userdriver.UserDriverManager;
import com.google.android.things.userdriver.UserSensor;
import com.google.android.things.userdriver.UserSensorDriver;
import com.google.android.things.userdriver.UserSensorReading;

import java.io.IOException;
import java.util.UUID;

public class Sht1xSensorDriver implements AutoCloseable {
    private static final String TAG = "Sht1xSensorDriver";

    // DRIVER parameters
    // documented at https://source.android.com/devices/sensors/hal-interface.html#sensor_t
    private static final String DRIVER_VENDOR = "Sensirion";
    private static final String DRIVER_NAME = "SHT10/11/15";
    // Our driver doesn't make measurement more than once every 5 seconds.
    private static final int DRIVER_MIN_DELAY_US = Sht1x.SHT1X_MEASUREMENT_INTERVAL;
    // The maximum value seems pretty arbitrary, so we just say double the minimum
    private static final int DRIVER_MAX_DELAY_US = Sht1x.SHT1X_MEASUREMENT_INTERVAL * 2;
    private Sht1x mDevice;

    private TemperatureUserDriver mTemperatureUserDriver;
    private HumidityUserDriver mHumidityUserDriver;

    /**
     * Create a new SHT1x sensor driver connected to the given pins.
     * The driver emits {@link android.hardware.Sensor} with temperature and humidity data when
     * registered.  Assumes a Vdd of 3.3v connected to the sensor.
     * @param gpioData Pin connected to SCK on the sensor.
     * @param gpioSck Pin connected to SCK on the sensor.
     * @throws IOException Sensor error
     * @see #registerTemperatureSensor()
     * @see #registerHumiditySensor()
     */
    public Sht1xSensorDriver(String gpioData, String gpioSck) throws IOException {
        mDevice = new Sht1x(gpioData, gpioSck);
    }

    /**
     * Create a new SHT1x sensor driver connected to the given pins.
     * The driver emits {@link android.hardware.Sensor} with temperature and humidity data when
     * registered.
     * @param gpioData Pin connected to SCK on the sensor.
     * @param gpioSck Pin connected to SCK on the sensor.
     * @param vdd Supply voltage (Vdd) used to power the sensor.
     * @throws IOException Sensor error
     * @see #registerTemperatureSensor()
     * @see #registerHumiditySensor()
     */
    public Sht1xSensorDriver(String gpioData, String gpioSck, float vdd) throws IOException {
        mDevice = new Sht1x(gpioData, gpioSck, vdd);
    }

    /**
     * Close the driver and the underlying device.
     * @throws IOException
     */
    @Override
    public void close() throws IOException {
        unregisterTemperatureSensor();
        unregisterHumiditySensor();
        if (mDevice != null) {
            try {
                mDevice.close();
            } finally {
                mDevice = null;
            }
        }
    }

    /**
     * Register a {@link UserSensor} that pipes temperature readings into the Android SensorManager.
     * @see #unregisterTemperatureSensor()
     */
    public void registerTemperatureSensor() {
        if (mDevice == null) {
            throw new IllegalStateException("cannot register closed driver");
        }

        if (mTemperatureUserDriver == null) {
            mTemperatureUserDriver = new TemperatureUserDriver();
            UserDriverManager.getManager().registerSensor(mTemperatureUserDriver.getUserSensor());
        }
    }

    /**
     * Register a {@link UserSensor} that pipes humidity readings into the Android SensorManager.
     * @see #unregisterHumiditySensor()
     */
    public void registerHumiditySensor() {
        if (mDevice == null) {
            throw new IllegalStateException("cannot register closed driver");
        }

        if (mHumidityUserDriver == null) {
            mHumidityUserDriver = new HumidityUserDriver();
            UserDriverManager.getManager().registerSensor(mHumidityUserDriver.getUserSensor());
        }
    }

    /**
     * Unregister the temperature {@link UserSensor}.
     */
    public void unregisterTemperatureSensor() {
        if (mTemperatureUserDriver != null) {
            UserDriverManager.getManager().unregisterSensor(mTemperatureUserDriver.getUserSensor());
            mTemperatureUserDriver = null;
        }
    }

    /**
     * Unregister the humidity {@link UserSensor}.
     */
    public void unregisterHumiditySensor() {
        if (mHumidityUserDriver != null) {
            UserDriverManager.getManager().unregisterSensor(mHumidityUserDriver.getUserSensor());
            mHumidityUserDriver = null;
        }
    }

    private void maybeStop() throws IOException {
        if ((mTemperatureUserDriver == null || !mTemperatureUserDriver.isEnabled()) &&
                (mHumidityUserDriver == null || !mHumidityUserDriver.isEnabled())) {
            mDevice.stop();
        } else {
            mDevice.start();
        }
    }

    private class TemperatureUserDriver extends UserSensorDriver {
        private static final int DRIVER_VERSION = 1;
        private static final String DRIVER_REQUIRED_PERMISSION = "";

        private boolean mEnabled;
        private UserSensor mUserSensor;

        private UserSensor getUserSensor() {
            if (mUserSensor == null) {
                mUserSensor = new UserSensor.Builder()
                        .setType(Sensor.TYPE_AMBIENT_TEMPERATURE)
                        .setName(DRIVER_NAME)
                        .setVendor(DRIVER_VENDOR)
                        .setVersion(DRIVER_VERSION)
                        .setMaxRange(Sht1x.SHT1X_TEMPERATURE_MAX)
                        .setResolution(Sht1x.SHT1X_TEMPERATURE_RESOLUTION)
                        .setPower(Sht1x.SHT1X_POWER_CONSUMPTION_UA)
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
            return new UserSensorReading(new float[]{mDevice.readTemperature()});
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

    private class HumidityUserDriver extends UserSensorDriver {
        private static final int DRIVER_VERSION = 1;
        private static final String DRIVER_REQUIRED_PERMISSION = "";

        private boolean mEnabled;
        private UserSensor mUserSensor;

        private UserSensor getUserSensor() {
            if (mUserSensor == null) {
                mUserSensor = new UserSensor.Builder()
                        .setType(Sensor.TYPE_RELATIVE_HUMIDITY)
                        .setName(DRIVER_NAME)
                        .setVendor(DRIVER_VENDOR)
                        .setVersion(DRIVER_VERSION)
                        .setMaxRange(Sht1x.SHT1X_HUMIDITY_MAX)
                        .setResolution(Sht1x.SHT1X_HUMIDITY_RESOLUTION)
                        .setPower(Sht1x.SHT1X_POWER_CONSUMPTION_UA)
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
            return new UserSensorReading(new float[]{mDevice.readHumidity()});
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
