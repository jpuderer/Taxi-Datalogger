package net.jpuderer.android.things.taxidatalogger.ble;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.provider.BaseColumns;
import android.util.Log;

import net.jpuderer.android.things.taxidatalogger.DatalogDbHelper;

import org.msgpack.core.MessageBufferPacker;
import org.msgpack.core.MessagePack;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class DataLoggerGattService extends BluetoothGattService implements GattServiceCallback {
    /**
     * Implementation of the custom datalogger profile.  Based very loosely on the Record Access
     * Control Point (RACP) method used
     * by the Glucose service:
     *     https://www.bluetooth.com/specifications/gatt/viewer?attributeXmlFile=org.bluetooth.service.glucose.xml
     */
    private static final String TAG = DataLoggerGattService.class.getSimpleName();

    /* Datalogger UUID */
    public static UUID DATA_LOGGER_SERVICE = UUID.fromString("3cf21710-a308-4c97-a8f9-f166e9ab429a");
    /**
     * A write-only query characteristic for fetching data from the endpoint.  Data is returned
     * using a series of indications.  Somewhat similar to the RACP, except that both the query
     * and data are encoded using MessagePack.
     */
    public static UUID DATA_LOGGER_QUERY = UUID.fromString("9ff88b40-6c90-4a26-8413-6235cf92028b");

    /* Client Characteristic Config Descriptor (standard characteristic) */
    public static UUID CLIENT_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    // Collection of indication subscribers
    private Set<BluetoothDevice> mRegisteredDevices = new HashSet<>();

    private BluetoothGattServer mBluetoothGattServer;

    private SQLiteDatabase mDatabase;

    public DataLoggerGattService(BluetoothGattServer bluetoothGattserver, SQLiteDatabase database) {
        super(DATA_LOGGER_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY);

        mBluetoothGattServer = bluetoothGattserver;
        mDatabase = database;

        // Resource access control point
        BluetoothGattCharacteristic racp = new BluetoothGattCharacteristic(DATA_LOGGER_QUERY,
                // Read-only characteristic, supports notifications
                BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_INDICATE,
                BluetoothGattCharacteristic.PERMISSION_WRITE);
        BluetoothGattDescriptor configDescriptor = new BluetoothGattDescriptor(CLIENT_CONFIG,
                // Read/write descriptor
                BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE);
        racp.addDescriptor(configDescriptor);

        addCharacteristic(racp);
    }

    @Override
    public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
        if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            // Remove device from any active subscriptions
            mRegisteredDevices.remove(device);
        }
    }

    @Override
    public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset,
                                            BluetoothGattCharacteristic characteristic) {
        // For now, there are no valid characteristics to read

        // Invalid characteristic
        Log.w(TAG, "Invalid Characteristic Read: " + characteristic.getUuid());
        mBluetoothGattServer.sendResponse(device,
                requestId,
                BluetoothGatt.GATT_FAILURE,
                0,
                null);
    }

    @Override
    public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                                             BluetoothGattCharacteristic characteristic,
                                             boolean preparedWrite, boolean responseNeeded,
                                             int offset, byte[] value) {
        if (DATA_LOGGER_QUERY.equals(characteristic.getUuid())) {
            mBluetoothGattServer.sendResponse(device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    0,
                    null);
            // TODO NEXT: Decode the request (we need to invent a query language using MessagePack)
            // For now, return the last 100 records as MessagePack
            try {
                executeQuery(device, requestId, characteristic, value);
            } catch (IOException e) {
                Log.e(TAG, "Error encoding response", e);
            }
        } else {
            // Invalid characteristic
            Log.w(TAG, "Invalid Characteristic Write: " + characteristic.getUuid());
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
        if (CLIENT_CONFIG.equals(descriptor.getUuid())) {
            Log.d(TAG, "Config descriptor read");
            byte[] returnValue;
            if (mRegisteredDevices.contains(device)) {
                returnValue = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
            } else {
                returnValue = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE;
            }
            mBluetoothGattServer.sendResponse(device,
                    requestId,
                    BluetoothGatt.GATT_FAILURE,
                    0,
                    returnValue);
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
        if (CLIENT_CONFIG.equals(descriptor.getUuid())) {
            if (Arrays.equals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE, value)) {
                Log.d(TAG, "Subscribe device to indications: " + device);
                mRegisteredDevices.add(device);
            } else if (Arrays.equals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE, value)) {
                Log.d(TAG, "Unsubscribe device from indications: " + device);
                mRegisteredDevices.remove(device);
            }

            if (responseNeeded) {
                mBluetoothGattServer.sendResponse(device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        0,
                        null);
            }
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

    // Execute the query.  For each row, encode the results of each row using MessagePack, and send
    // it as an indication (a notification with acknowledgement)
    private void executeQuery(BluetoothDevice device, int requestId,
                              BluetoothGattCharacteristic characteristic, byte[] query) throws IOException {
        Cursor cursor = mDatabase.query(DatalogDbHelper.TABLE_NAME,
                null,
                null,
                null,
                null,
                null,
                BaseColumns._ID + " DESC", "100");
        for (cursor.moveToFirst(); !cursor.isAfterLast(); cursor.moveToNext()) {
            ContentValues values = new ContentValues();
            DatabaseUtils.cursorRowToContentValues(cursor, values);

            // FIXME: Update this for the new data format
            MessageBufferPacker packer = MessagePack.newDefaultBufferPacker();
            final long id = values.getAsLong(DatalogDbHelper.DatalogEntry._ID);
            packer.packLong(id);
            final long time =
                    values.getAsLong(DatalogDbHelper.DatalogEntry.COLUMN_NAME_TIME);
            packer.packLong(time);
            final float temperature =
                    values.getAsFloat(DatalogDbHelper.DatalogEntry.COLUMN_NAME_TEMPERATURE);
            packer.packFloat(temperature);
            final float humidity =
                    values.getAsFloat(DatalogDbHelper.DatalogEntry.COLUMN_NAME_HUMIDITY);
            packer.packFloat(humidity);
            packer.close();

            characteristic.setValue(packer.toByteArray());
            mBluetoothGattServer.notifyCharacteristicChanged(device, characteristic,
                    true);
        }
        cursor.close();
    }
}
