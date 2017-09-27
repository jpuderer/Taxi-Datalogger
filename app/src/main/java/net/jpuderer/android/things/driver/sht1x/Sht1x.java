package net.jpuderer.android.things.driver.sht1x;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import com.google.android.things.pio.Gpio;
import com.google.android.things.pio.GpioCallback;
import com.google.android.things.pio.PeripheralManagerService;

import java.io.IOException;

// TODO Note: Assumptions: 12bit mode, measurements in Celcius.
// TODO: Good sensor docs for MMA7660FC
// TODO: Return the last value if called in under a second
// TODO: Avoid blocking the thread
//       Cap12xx.java is a good example of how to delay, and trigger on interrupt.
//       I should be using a callback to return results.
@SuppressLint("DefaultLocale")
public class Sht1x implements AutoCloseable {
    private static final String TAG = Sht1x.class.getSimpleName();

    private static final int SHT1X_CMD_MEASURE_TEMPERATURE = 0b00000011;
    private static final int SHT1X_CMD_MEASURE_HUMIDITY = 0b00000101;
    private static final int SHT1X_CMD_READ_STATUS = 0b00000111;
    private static final int SHT1X_CMD_WRITE_STATUS = 0b00000110;
    private static final int SHT1X_SOFT_RESET = 0b00011110;

    // Wait up to 1000ms for data from a measurement command
    private static final long SHT1X_MEASUREMENT_TIMEOUT = 1000;

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

    private PeripheralManagerService mPeripheralManager;
    private Gpio mGpioData;
    private Gpio mGpioSck;

    private Handler mHandler;

    private int mRawTemperature;
    private int mRawHumidity;

    // We calculate the D1 constant, since it varies according to supply
    // voltage and can be represented as a simple linear formula.
    private float mD1;  // Gets assigned in the constructor
    private float calculateD1(float vdd) {
        return (-0.267568f * vdd + -38.756757f);
    }



    public interface OnMeasurementCallback {
        /**
         * Called when a temperature measurement completes
         *
         * @param temperature temperature in Celsius
         * @param humidity relative humidity (RH) as a percentage
         */
        void onMeasurement(float temperature, float humidity);

        /**
         * Called when measurement encounters an error
         */
        void onIOException(IOException e);
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

    /**
     * Release the GPIO pins
     */
    @Override
    public void close() throws IOException {
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

    // Start a measurement of both temperature and humidity
    // FIXME: better description.  proper docs
    public void getMeasurement(final OnMeasurementCallback callback) {
        // TODO: Return stored value if last measurement was less than a second ago.

        final Object timeoutToken = new Object();

        final GpioCallback gpioCallback = new GpioCallback() {
            @Override
            public boolean onGpioEdge(Gpio gpio) {
                mHandler.removeCallbacksAndMessages(timeoutToken);
                gpio.unregisterGpioCallback(this);
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        finishTempMeasurement(callback);
                    }
                });
                return false; // Do not listen for more events
            }

            @Override
            public void onGpioError(Gpio gpio, int error) {
                mHandler.removeCallbacksAndMessages(timeoutToken);
                callback.onIOException(new IOException("GPIO error: " + error));
            }
        };

        Runnable measurementTimeout = new Runnable() {
            @Override
            public void run() {
                mGpioData.unregisterGpioCallback(gpioCallback);
                callback.onIOException(new IOException("Timeout waiting for measurement."));
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
            callback.onIOException(e);
            return;
        }
    }

    // Collect the temp data, then send to command to collect the humidity data
    private void finishTempMeasurement(final OnMeasurementCallback callback) {
        try {
            mRawTemperature = readData();
            skipCrc();
        } catch (IOException e) {
            callback.onIOException(e);
            return;
        }

        final Object timeoutToken = new Object();

        final GpioCallback gpioCallback = new GpioCallback() {
            @Override
            public boolean onGpioEdge(Gpio gpio) {
                mHandler.removeCallbacksAndMessages(timeoutToken);
                gpio.unregisterGpioCallback(this);
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        finishRhMeasurement(callback);
                    }
                });
                return false; // Do not listen for more events
            }

            @Override
            public void onGpioError(Gpio gpio, int error) {
                mHandler.removeCallbacksAndMessages(timeoutToken);
                callback.onIOException(new IOException("GPIO error: " + error));
            }
        };

        Runnable measurementTimeout = new Runnable() {
            @Override
            public void run() {
                mGpioData.unregisterGpioCallback(gpioCallback);
                callback.onIOException(new IOException("Timeout waiting for measurement."));
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
            callback.onIOException(e);
            return;
        }
    }

    void finishRhMeasurement(final OnMeasurementCallback callback) {
        try {
            mRawHumidity = readData();
            skipCrc();
        } catch (IOException e) {
            callback.onIOException(e);
            return;
        }
        returnMeasurement(callback);
    }

    void returnMeasurement(final OnMeasurementCallback callback) {
        float temperature = (mD1 + D2 * mRawTemperature);
        float rhLinear = C1 + C2 * mRawHumidity + C3 * mRawHumidity * mRawHumidity;
        float rhTrue = (temperature - 25) * (T1 + T2 * mRawHumidity) + rhLinear;
        if (rhTrue > 100) {
            rhTrue = 100;
        } else if (rhTrue < 0) {
            rhTrue = 0;
        }
        callback.onMeasurement(temperature, rhTrue);
    }

    /**
     * Read the current temperature.
     *
     * @return the current temperature in degrees Celsius
     */
    private float readTemperature() throws IOException {
        int rawTemp = readRawTemperature();
        return (mD1 + D2 * rawTemp);
    }

    /**
     * Read the current humidity.
     *
     * @return the current relative humidity in RH percentage (100f means totally saturated air)
     */
    private float readHumidity() throws IOException {
        int rawHumidity = readRawHumidity();
        float temperature = readTemperature();

        float rhLinear = C1 + C2 * rawHumidity + C3 * rawHumidity * rawHumidity;
        float rhTrue = (temperature - 25) * (T1 + T2 * rawHumidity) + rhLinear;
        if (rhTrue > 100) {
            rhTrue = 100;
        } else if (rhTrue < 0) {
            rhTrue = 0;
        }
        return rhTrue;
    }

    private int readRawTemperature() throws IOException {
        sendCommand(SHT1X_CMD_MEASURE_TEMPERATURE);
        waitForResult();
        int rawTemperature = readData();
        skipCrc();
        return rawTemperature;
    }

    private int readRawHumidity() throws IOException {
        sendCommand(SHT1X_CMD_MEASURE_HUMIDITY);
        waitForResult();
        int rawHumidity = readData();
        skipCrc();
        return rawHumidity;
    }

    private void sendCommand(int command) throws IOException {
        mGpioData.setDirection(Gpio.DIRECTION_OUT_INITIALLY_HIGH);
        mGpioSck.setDirection(Gpio.DIRECTION_OUT_INITIALLY_HIGH);

        mGpioData.setValue(true); // needed?
        mGpioSck.setValue(true); // needed?
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

    // After receiving a measurement command, the DATA line
    // should be initially high, then pulled low to indicate
    // the data is ready.  The measurement time should take about
    // 320ms (for 14bit values)
    private void waitForResult() {
        // Something that gets called when data goes low,
        // or a timeout (1s) is reached.
        // FIXME: Implement me properly!
        for (int i=0; i < 100; i++) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                // DO NOTHING
            }
        }
    }

    private int readData() throws IOException {
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
        mGpioData.setDirection(Gpio.DIRECTION_OUT_INITIALLY_HIGH);
        mGpioSck.setDirection(Gpio.DIRECTION_OUT_INITIALLY_HIGH);
        waitSckCycle();
        mGpioSck.setValue(false);
        waitSckCycle();
    }

    // Reset device state.  Can be useful if the device has a pending result that
    // was never retrieved, or has interpreted some line noise as an SCK pulse.
    private void connectionReset() throws IOException {
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