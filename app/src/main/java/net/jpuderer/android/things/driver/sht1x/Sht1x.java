package net.jpuderer.android.things.driver.sht1x;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.google.android.things.pio.Gpio;
import com.google.android.things.pio.GpioCallback;
import com.google.android.things.pio.PeripheralManagerService;

import java.io.IOException;
import java.util.Timer;
import java.util.TimerTask;

// TODO Note: Assumptions: 12bit mode, measurements in Celcius.
// TODO: Good sensor docs for MMA7660FC
@SuppressLint("DefaultLocale")
public class Sht1x implements AutoCloseable {
    private static final String TAG = Sht1x.class.getSimpleName();

    private static final int SHT1X_CMD_MEASURE_TEMPERATURE = 0b00000011;
    private static final int SHT1X_CMD_MEASURE_HUMIDITY = 0b00000101;
    private static final int SHT1X_CMD_READ_STATUS = 0b00000111;
    private static final int SHT1X_CMD_WRITE_STATUS = 0b00000110;
    private static final int SHT1X_SOFT_RESET = 0b00011110;

    // Wait up to 1000ms for data from a measurement command
    private static final int SHT1X_MEASUREMENT_TIMEOUT = 1000;

    // Vdd ranges from the data sheet
    private static final float SHT1X_VDD_MIN = 2.4f;
    private static final float SHT1X_VDD_TYPICAL = 3.3f;
    private static final float SHT1X_VDD_MAX = 5.5f;

    // Constants from data sheet for calculating temperature and humidity
    // in Celsius with 18bit sensor resolution.
    private static final float C1 = -2.0468f;
    private static final float C2 = 0.0367f;
    private static final float C3 = -1.5955e-6f;
    private static final float T1 = 0.01f;
    private static final float T2 = 0.00008f;
    private static final float D2 = 0.01f;

    // According to the datasheet, the sensor should not be active for
    // more than 10% of the time to prevent self heating.  Sampling every
    // 5 seconds gives us lots of room to spare.
    public static final int SHT1X_MEASUREMENT_INTERVAL = 5000;

    public static final float SHT1X_TEMPERATURE_RESOLUTION = 0.01f;
    public static final float SHT1X_TEMPERATURE_MAX = 123.8f;

    public static final float SHT1X_HUMIDITY_RESOLUTION = 0.04f;
    public static final float SHT1X_HUMIDITY_MAX = 100.0f;

    // Power consumption is a bit of a guess, since if the user has registered
    // both temperature and humidity sensors, the consumption is counted twice.
    public static final float SHT1X_POWER_CONSUMPTION_UA = 90;

    private PeripheralManagerService mPeripheralManager;
    private Gpio mGpioData;
    private Gpio mGpioSck;

    private Handler mHandler;
    private Timer mTimer;

    // Is the sensor started (making measurements)?
    private boolean mStarted;

    private float mTemperature;
    private float mHumidity;

    // If set, stored exception to throw when user asks for data
    private IOException mLastException;

    // We calculate the D1 constant, since it varies according to supply
    // voltage and can be represented as a simple linear formula.
    private float mD1;  // Gets assigned in the constructor
    private float calculateD1(float vdd) {
        return (-0.267568f * vdd + -38.756757f);
    }

    /**
     * Create a new SHT1x sensor driver attached to the given pins.
     * @param gpioData Pin connected to SCK on the sensor.
     * @param gpioSck Pin connected to SCK on the sensor.
     * @throws IOException Sensor error
     */
    public Sht1x(String gpioData, String gpioSck) throws IOException {
        this(gpioData, gpioSck, SHT1X_VDD_TYPICAL, null);
    }

    /**
     * Create a new SHT1x sensor driver attached to the given pins.
     * @param gpioData Pin connected to SCK on the sensor.
     * @param gpioSck Pin connected to SCK on the sensor.
     * @param vdd Supply voltage (Vdd) used to power the sensor.
     * @throws IOException Sensor error
     */
    public Sht1x(String gpioData, String gpioSck, float vdd) throws IOException {
        this(gpioData, gpioSck, SHT1X_VDD_TYPICAL, null);
    }

    /**
     * Create a new SHT1x sensor driver attached to the given GPIOs.
     * @param gpioData Pin connected to SCK on the sensor.
     * @param gpioSck Pin connected to SCK on the sensor.
     * @param vdd Supply voltage (Vdd) used to power the sensor.
     * @param handler
     * @throws IOException Sensor error
     */
    public Sht1x(String gpioData, String gpioSck, float vdd, Handler handler) throws IOException {
        if ((vdd < SHT1X_VDD_MIN) || (vdd > SHT1X_VDD_MAX)) {
            final String msg = String.format("Vdd must be between %.1f and %.1f",
                    SHT1X_VDD_MIN, SHT1X_VDD_MAX);
            throw new IllegalArgumentException(msg);
        }
        mD1 = calculateD1(vdd);

        // Get the default handler if handler is not specified
        mHandler = new Handler(handler == null ? Looper.myLooper() : handler.getLooper());

        // Timer for scheduling measurements.
        mTimer = new Timer();

        mPeripheralManager = new PeripheralManagerService();
        try {
            mGpioData = mPeripheralManager.openGpio(gpioData);
            mGpioData.setActiveType(Gpio.ACTIVE_HIGH);

            mGpioSck = mPeripheralManager.openGpio(gpioSck);
            mGpioSck.setActiveType(Gpio.ACTIVE_HIGH);

            // Reset the connection, in case the sensor is in a strange state.
            connectionReset();
        } catch (IOException|RuntimeException e) {
            try {
                close();
            } catch (IOException|RuntimeException ignored) {
            }
            throw e;
        }
    }

    // Start making sensor measurements
    public void start() {
        synchronized (this) {
            if (mStarted) return;
            mLastException = new IOException("No data available");
            mStarted = true;
            mTimer = new Timer();
            mTimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    doMeasurements();
                }
            }, 0, SHT1X_MEASUREMENT_INTERVAL);
        }
    }

    // Stop making sensor measurements
    public void stop() {
        synchronized (this) {
            if (!mStarted) return;
            mStarted = false;
            mTimer.cancel();
            mTimer = null;
        }
    }

    /**
     * Release the GPIO pins
     */
    @Override
    public void close() throws IOException {
        stop();
        if (mGpioData != null) {
            try {
                mGpioData.close();
            } finally {
                mGpioData = null;
                close();
            }
        }
        if (mGpioSck != null) {
            try {
                mGpioSck.close();
            } finally {
                mGpioSck = null;
            }
        }
    }

    public float readTemperature() throws IOException {
        if (!mStarted) {
            throw new IOException("Sensor has not started");
        } else if (mLastException != null) {
            throw mLastException;
        }
        return mTemperature;
    }

    public float readHumidity() throws IOException {
        if (!mStarted) {
            throw new IOException("Sensor has not started");
        } else if (mLastException != null) {
            throw mLastException;
        }
        return mHumidity;
    }

    private  void doMeasurements() {
        final Object timeoutToken = new Object();

        final GpioCallback gpioCallback = new GpioCallback() {
            @Override
            public boolean onGpioEdge(Gpio gpio) {
                mHandler.removeCallbacksAndMessages(timeoutToken);
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        finishTempMeasurement();
                    }
                });
                return false; // Do not listen for more events
            }

            @Override
            public void onGpioError(Gpio gpio, int error) {
                mHandler.removeCallbacksAndMessages(timeoutToken);
                mLastException = new IOException("GPIO error: " + error);
            }
        };

        Runnable measurementTimeout = new Runnable() {
            @Override
            public void run() {
                mGpioData.unregisterGpioCallback(gpioCallback);
                mLastException = new IOException("Timeout waiting for temperature measurement.");
            }
        };

        try {
            // Send the measurement command
            sendCommand(SHT1X_CMD_MEASURE_TEMPERATURE);

            mGpioData.setDirection(Gpio.DIRECTION_IN);
            mGpioData.setEdgeTriggerType(Gpio.EDGE_FALLING);

            // Register the callback for when data is available
            mGpioData.registerGpioCallback(gpioCallback, mHandler);

            // Set a timeout, in case we don't receive data.
            mHandler.postAtTime(measurementTimeout, timeoutToken,
                    SystemClock.uptimeMillis() + SHT1X_MEASUREMENT_TIMEOUT);
        } catch (IOException e) {
            mGpioData.unregisterGpioCallback(gpioCallback);
            mHandler.removeCallbacksAndMessages(timeoutToken);
            mLastException = e;
        }
    }

    // Collect the temperature measurement, and invoke the callback
    private void finishTempMeasurement() {
        int rawTemperature;
        try {
            rawTemperature = readData();
            skipCrc();
        } catch (IOException e) {
            mLastException = e;
            return;
        }
        mTemperature = (mD1 + D2 * rawTemperature);
        getHumidityMeasurement();
    }

    private void getHumidityMeasurement() {
        final Object timeoutToken = new Object();

        final GpioCallback gpioCallback = new GpioCallback() {
            @Override
            public boolean onGpioEdge(Gpio gpio) {
                mHandler.removeCallbacksAndMessages(timeoutToken);
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        finishMeasurements();
                    }
                });
                return false; // Do not listen for more events
            }

            @Override
            public void onGpioError(Gpio gpio, int error) {
                mHandler.removeCallbacksAndMessages(timeoutToken);
                mLastException = new IOException("GPIO error: " + error);
            }
        };

        Runnable measurementTimeout = new Runnable() {
            @Override
            public void run() {
                mGpioData.unregisterGpioCallback(gpioCallback);
                mLastException = new IOException("Timeout waiting for humidity measurement.");
            }
        };

        try {
            // Send the measurement command
            sendCommand(SHT1X_CMD_MEASURE_HUMIDITY);

            mGpioData.setDirection(Gpio.DIRECTION_IN);
            mGpioData.setEdgeTriggerType(Gpio.EDGE_FALLING);

            // Register the callback for when data is available
            mGpioData.registerGpioCallback(gpioCallback, mHandler);

            // Set a timeout, in case we don't receive data.
            mHandler.postAtTime(measurementTimeout, timeoutToken,
                    SystemClock.uptimeMillis() + SHT1X_MEASUREMENT_TIMEOUT);
        } catch (IOException e) {
            mGpioData.unregisterGpioCallback(gpioCallback);
            mHandler.removeCallbacksAndMessages(timeoutToken);
            mLastException = e;
        }
    }

    private void finishMeasurements() {
        int rawHumidity;
        try {
            rawHumidity = readData();
            skipCrc();
        } catch (IOException e) {
            mLastException = e;
            return;
        }
        float rhLinear = C1 + C2 * rawHumidity + C3 * rawHumidity * rawHumidity;
        float humidity = (mTemperature - 25) * (T1 + T2 * rawHumidity) + rhLinear;
        if (humidity > 100) {
            humidity = 100;
        } else if (humidity < 0) {
            humidity = 0;
        }
        mHumidity = humidity;
        // Clear exception (if present)
        mLastException = null;
    }

    private void sendCommand(int command) throws IOException {
        // Make sure there is no edge trigger set.  See:
        //     https://issuetracker.google.com/issues/66972799
        mGpioData.setEdgeTriggerType(Gpio.EDGE_NONE);

        mGpioData.setDirection(Gpio.DIRECTION_OUT_INITIALLY_HIGH);
        mGpioSck.setDirection(Gpio.DIRECTION_OUT_INITIALLY_HIGH);
        waitSckCycle();

        // Send "Transmission start sequence"
        mGpioData.setValue(false);
        mGpioSck.setValue(false);
        waitSckCycle();
        mGpioSck.setValue(true);
        waitSckCycle();
        mGpioData.setValue(true);
        mGpioSck.setValue(false);
        waitSckCycle();

        // Send the command
        for (int i = 0; i < 8; i++) {
            final boolean bit = (command & (1 << 7 - i)) != 0;
            mGpioData.setValue(bit);
            mGpioSck.setValue(true);
            waitSckCycle();
            mGpioSck.setValue(false);
            waitSckCycle();
        }

        // Sensor should acknowledge command by changing the DATA line
        // from high to low when we pulse SCK.
        mGpioData.setDirection(Gpio.DIRECTION_IN);
        mGpioSck.setValue(true);
        waitSckCycle();

        boolean ack = !mGpioData.getValue();
        if (!ack)
            throw new IOException(String.format("Sensor did not send command ACK: 0x%x", command));

        mGpioSck.setValue(false);
        waitSckCycle();
        ack = mGpioData.getValue();

        if (!ack)
            throw new IOException(
                    String.format("Sensor did not send measurement ACK for command: 0x%x", command));
    }

    // Small busy wait of 100ns.  Busy waiting is normally not a great idea, but 100ns
    // is a very small amount of time to wait, and can't actually wait any other way for
    // so small an amount of time without having access to a high resolution timer.
    private void waitSckCycle() {
        long end = System.nanoTime() + 100;
        long current;
        do {
            current = System.nanoTime();
        } while (end >= current);
    }

    private int readData() throws IOException {
        // Make sure there is no edge trigger set.  See:
        //     https://issuetracker.google.com/issues/66972799
        mGpioData.setEdgeTriggerType(Gpio.EDGE_NONE);

        mGpioData.setDirection(Gpio.DIRECTION_IN);
        mGpioSck.setDirection(Gpio.DIRECTION_OUT_INITIALLY_HIGH);

        // Get the most significant bits
        int value = shiftInByte();
        value = value << 8;

        // Send the required ACK
        mGpioData.setDirection(Gpio.DIRECTION_OUT_INITIALLY_HIGH);
        waitSckCycle();
        mGpioData.setValue(false);
        mGpioSck.setValue(true);
        waitSckCycle();
        mGpioSck.setValue(false);
        waitSckCycle();

        // Get the least significant bits
        mGpioData.setDirection(Gpio.DIRECTION_IN);
        value |= shiftInByte();

        return value;
    }

    // Shift in one byte
    private int shiftInByte() throws IOException {
        int value = 0;
        for (int i = 0; i < 8; i++) {
            mGpioSck.setValue(true);
            waitSckCycle();
            value = value * 2 + (mGpioData.getValue() ? 1 : 0);
            mGpioSck.setValue(false);
            waitSckCycle();
        }
        return value;
    }

    private void skipCrc() throws IOException {
        // Make sure there is no edge trigger set.  See:
        //     https://issuetracker.google.com/issues/66972799
        mGpioData.setEdgeTriggerType(Gpio.EDGE_NONE);

        mGpioData.setDirection(Gpio.DIRECTION_OUT_INITIALLY_HIGH);
        mGpioSck.setDirection(Gpio.DIRECTION_OUT_INITIALLY_HIGH);
        waitSckCycle();
        mGpioSck.setValue(false);
        waitSckCycle();
    }

    // Reset device state.  Can be useful if the device has a pending result that
    // was never retrieved, or has interpreted some line noise as an SCK pulse.
    private void connectionReset() throws IOException {
        // Make sure there is no edge trigger set.  See:
        //     https://issuetracker.google.com/issues/66972799
        mGpioData.setEdgeTriggerType(Gpio.EDGE_NONE);

        mGpioData.setDirection(Gpio.DIRECTION_OUT_INITIALLY_HIGH);
        mGpioSck.setDirection(Gpio.DIRECTION_OUT_INITIALLY_HIGH);
        for (int i=0; i < 10; i++) {
            mGpioSck.setValue(true);
            waitSckCycle();
            mGpioSck.setValue(false);
            waitSckCycle();
        }
    }
}