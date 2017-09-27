package net.jpuderer.android.things.storagedatalogger;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;

import net.jpuderer.android.things.driver.sht1x.Sht1x;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Timer;
import java.util.TimerTask;

public class MainActivity extends Activity {
    private static final String TAG = "StorageDataLogger" ;
    Handler mHandler;
    Sht1x mSensor;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mHandler = new Handler();
        try {
            mSensor = new Sht1x("BCM17", "BCM27", 3.3f);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        Timer timer = new Timer();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                mSensor.getMeasurement(new Sht1x.OnMeasurementCallback() {
                    @Override
                    public void onMeasurement(float temperature, float humidity) {
                        Log.d(TAG, String.format("Temperature: %.1f, Humidity %.1f",
                                temperature, humidity));
                    }

                    @Override
                    public void onIOException(IOException e) {
                        e.printStackTrace();
                    }
                });
            }
        }, 0, 10000);
    }
}
