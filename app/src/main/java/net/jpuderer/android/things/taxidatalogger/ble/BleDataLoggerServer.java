package net.jpuderer.android.things.taxidatalogger.ble;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.sqlite.SQLiteDatabase;
import android.os.IBinder;
import android.util.Log;

import net.jpuderer.android.things.taxidatalogger.DatalogDbHelper;

import org.jetbrains.annotations.Nullable;

import java.util.UUID;

// FIXME: Bluetooth adapter seems to frequently crash or hang.  Find a way to reset it.
public class BleDataLoggerServer extends Service {
    private static final String TAG = BleDataLoggerServer.class.getSimpleName();
    // Database of logging entries
    DatalogDbHelper mDbHelper;
    SQLiteDatabase mDb;

    // Bluetooth API
    private BluetoothManager mBluetoothManager;
    private BluetoothGattServer mBluetoothGattServer;
    private BluetoothLeAdvertiser mBluetoothLeAdvertiser;

    // Bluetooth Services
    private TimeGattService mTimeService;
    private DataLoggerGattService mDataLoggerService;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * Listens for system time changes and triggers a notification to
     * Bluetooth subscribers.
     */
    private BroadcastReceiver mTimeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            byte adjustReason;
            switch (intent.getAction()) {
                case Intent.ACTION_TIME_CHANGED:
                    adjustReason = TimeGattService.ADJUST_MANUAL;
                    break;
                case Intent.ACTION_TIMEZONE_CHANGED:
                    adjustReason = TimeGattService.ADJUST_TIMEZONE;
                    break;
                default:
                case Intent.ACTION_TIME_TICK:
                    adjustReason = TimeGattService.ADJUST_NONE;
                    break;
            }
            long now = System.currentTimeMillis();
            if (mTimeService != null) {
                mTimeService.notifyRegisteredDevices(now, adjustReason);
            }
        }
    };

    /**
     * Listens for Bluetooth adapter events to enable/disable
     * advertising and server functionality.
     */
    private BroadcastReceiver mBluetoothReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF);

            switch (state) {
                case BluetoothAdapter.STATE_ON:
                    startAdvertising();
                    startServer();
                    break;
                case BluetoothAdapter.STATE_OFF:
                    stopServer();
                    stopAdvertising();
                    break;
                default:
                    // Do nothing
            }
        }
    };

    /**
     * Callback to receive information about the advertisement process.
     */
    private AdvertiseCallback mAdvertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            Log.i(TAG, "LE Advertise Started.");
        }

        @Override
        public void onStartFailure(int errorCode) {
            Log.w(TAG, "LE Advertise Failed: "+errorCode);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();

        // Get DB and DB helper
        mDbHelper = new DatalogDbHelper(this);
        mDb = mDbHelper.getWritableDatabase();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        mBluetoothManager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        BluetoothAdapter bluetoothAdapter = mBluetoothManager.getAdapter();

        // We can't continue without proper Bluetooth support
        if (!checkBluetoothSupport(bluetoothAdapter)) {
            Log.w(TAG, "Not starting datalog service. No Bluetooth support.");
            stopSelf();
            return START_NOT_STICKY;
        }

        // Register for system clock events
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_TIME_TICK);
        filter.addAction(Intent.ACTION_TIME_CHANGED);
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(mTimeReceiver, filter);

        // Register for system Bluetooth events
        filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        registerReceiver(mBluetoothReceiver, filter);
        if (!bluetoothAdapter.isEnabled()) {
            Log.d(TAG, "Bluetooth is currently disabled...enabling");
            bluetoothAdapter.enable();
        } else {
            Log.d(TAG, "Bluetooth enabled...starting services");
            startAdvertising();
            startServer();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        BluetoothAdapter bluetoothAdapter = mBluetoothManager.getAdapter();
        if (bluetoothAdapter.isEnabled()) {
            stopServer();
            stopAdvertising();
        }
        unregisterReceiver(mTimeReceiver);
        unregisterReceiver(mBluetoothReceiver);
        super.onDestroy();
    }


    /**
     * Verify the level of Bluetooth support provided by the hardware.
     * @param bluetoothAdapter System {@link BluetoothAdapter}.
     * @return true if Bluetooth is properly supported, false otherwise.
     */
    private boolean checkBluetoothSupport(BluetoothAdapter bluetoothAdapter) {
        if (bluetoothAdapter == null) {
            Log.w(TAG, "Bluetooth is not supported");
            return false;
        }

        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Log.w(TAG, "Bluetooth LE is not supported");
            return false;
        }

        return true;
    }

    /**
     * Begin advertising over Bluetooth that this device is connectable
     * and supports the Current Time Service.
     */
    private void startAdvertising() {
        BluetoothAdapter bluetoothAdapter = mBluetoothManager.getAdapter();
        mBluetoothLeAdvertiser = bluetoothAdapter.getBluetoothLeAdvertiser();
        if (mBluetoothLeAdvertiser == null) {
            Log.w(TAG, "Failed to create advertiser");
            return;
        }

        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                .setConnectable(true)
                .setTimeout(0)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .build();

        // Set device name
        BluetoothAdapter.getDefaultAdapter().setName("Taxi");

        // We have to be very careful here as the advertising payload is only 31 bytes
        // with 2 byte header for each field.  Since, our service that we want to advertise
        // is custom, that means we have very few bytes for the name.
        //
        // We could exclude the device name, but it's nice to be able to quickly find the
        // device in tools like nRf Connect.
        //
        // FIXME: There appears to be a very odd exception in the logs if I add
        // a full 128 bit service ID.  After that, the BLE adapter behaves unreliably
        // and disconnects on some future transaction.
        AdvertiseData data = new AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .setIncludeTxPowerLevel(false)
                //.addServiceUuid(new ParcelUuid(DataLoggerGattService.DATA_LOGGER_SERVICE))
                .build();

        mBluetoothLeAdvertiser
                .startAdvertising(settings, data, mAdvertiseCallback);
    }

    /**
     * Stop Bluetooth advertisements.
     */
    private void stopAdvertising() {
        if (mBluetoothLeAdvertiser == null) return;
        mBluetoothLeAdvertiser.stopAdvertising(mAdvertiseCallback);
    }

    /**
     * Initialize the GATT server instance with the services/characteristics
     * from the Time Profile.
     */
    private void startServer() {
        mBluetoothGattServer = mBluetoothManager.openGattServer(this, mGattServerCallback);
        if (mBluetoothGattServer == null) {
            Log.w(TAG, "Unable to create GATT server");
            return;
        }

        mTimeService = new TimeGattService(mBluetoothGattServer);
        mBluetoothGattServer.addService(mTimeService);
        mDataLoggerService = new DataLoggerGattService(mBluetoothGattServer, mDb);
        mBluetoothGattServer.addService(mDataLoggerService);
    }

    /**
     * Shut down the GATT server.
     */
    private void stopServer() {
        if (mBluetoothGattServer == null) return;
        mBluetoothGattServer.close();
    }

    /**
     * Callback to handle incoming requests to the GATT server.
     * All read/write requests for characteristics and descriptors are handled here.
     */
    private BluetoothGattServerCallback mGattServerCallback = new BluetoothGattServerCallback() {

        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            if (mTimeService != null) {
                mTimeService.onConnectionStateChange(device, status, newState);
            }
            if (mDataLoggerService != null) {
                mDataLoggerService.onConnectionStateChange(device, status, newState);
            }
        }

        @Override
        public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset,
                                                BluetoothGattCharacteristic characteristic) {
            UUID serviceUuid  = characteristic.getService().getUuid();
            if (serviceUuid == TimeGattService.TIME_SERVICE) {
                mTimeService.onCharacteristicReadRequest(device, requestId, offset, characteristic);
            } else if (serviceUuid == DataLoggerGattService.DATA_LOGGER_SERVICE) {
                mDataLoggerService.onCharacteristicReadRequest(device, requestId, offset, characteristic);
            } else {
                // Invalid characteristic
                Log.w(TAG, "Invalid Characteristic Read: " + characteristic.getUuid());
                mBluetoothGattServer.sendResponse(device,
                        requestId,
                        BluetoothGatt.GATT_FAILURE,
                        0,
                        null);
            }
        }

        @Override
        public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                                                 BluetoothGattCharacteristic characteristic,
                                                 boolean preparedWrite, boolean responseNeeded,
                                                 int offset, byte[] value) {
            UUID serviceUuid  = characteristic.getService().getUuid();
            if (serviceUuid == TimeGattService.TIME_SERVICE) {
                mTimeService.onCharacteristicWriteRequest(device, requestId, characteristic,
                        preparedWrite, responseNeeded, offset, value);
            } else if (serviceUuid == DataLoggerGattService.DATA_LOGGER_SERVICE) {
                mDataLoggerService.onCharacteristicWriteRequest(device, requestId, characteristic,
                        preparedWrite, responseNeeded, offset, value);
            } else {
                // Invalid characteristic
                Log.w(TAG, "Invalid Characteristic Read: " + characteristic.getUuid());
                mBluetoothGattServer.sendResponse(device,
                        requestId,
                        BluetoothGatt.GATT_FAILURE,
                        0,
                        null);
            }
        }

        @Override
        public void onDescriptorReadRequest(BluetoothDevice device, int requestId, int offset,
                                            BluetoothGattDescriptor descriptor) {
            UUID serviceUuid  = descriptor.getCharacteristic().getService().getUuid();
            if (serviceUuid == TimeGattService.TIME_SERVICE) {
                mTimeService.onDescriptorReadRequest(device, requestId, offset, descriptor);
            } else if (serviceUuid == DataLoggerGattService.DATA_LOGGER_SERVICE) {
                mDataLoggerService.onDescriptorReadRequest(device, requestId, offset, descriptor);
            } else {
                Log.w(TAG, "Unknown descriptor read request");
                mBluetoothGattServer.sendResponse(device,
                        requestId,
                        BluetoothGatt.GATT_FAILURE,
                        0,
                        null);
            }
        }

        @Override
        public void onDescriptorWriteRequest(BluetoothDevice device, int requestId,
                                             BluetoothGattDescriptor descriptor,
                                             boolean preparedWrite, boolean responseNeeded,
                                             int offset, byte[] value) {
            UUID serviceUuid  = descriptor.getCharacteristic().getService().getUuid();
            if (serviceUuid == TimeGattService.TIME_SERVICE) {
                mTimeService.onDescriptorWriteRequest(device, requestId, descriptor, preparedWrite,
                        responseNeeded, offset, value);
            } else if (serviceUuid == DataLoggerGattService.DATA_LOGGER_SERVICE) {
                mDataLoggerService.onDescriptorWriteRequest(device, requestId, descriptor, preparedWrite,
                        responseNeeded, offset, value);
            } else {
                Log.w(TAG, "Unknown descriptor write request");
                if (responseNeeded) {
                    mBluetoothGattServer.sendResponse(device,
                            requestId,
                            BluetoothGatt.GATT_FAILURE,
                            0,
                            null);
                }
            }
        }
    };
}
