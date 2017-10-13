package net.jpuderer.android.things.taxidatalogger;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.provider.BaseColumns;

import static android.content.Context.MODE_PRIVATE;

public class DatalogDbHelper extends SQLiteOpenHelper {
    public static final int DATABASE_VERSION = 6;
    public static final String DATABASE_NAME = "Datalog.db";

    public static final String TABLE_NAME = "datalog";

    private static final String SYNC_SHARED_PREFERENCES_KEY = "sync_status" ;
    private static final String PREF_LAST_SYNCED_ID = "last_synced_id";

    public static class DatalogEntry implements BaseColumns {
        static final String TABLE_NAME = "datalog";
        public static final String COLUMN_NAME_TIME = "time"; // UTC seconds since epoch
        public static final String COLUMN_NAME_LATITUDE = "latitude";
        public static final String COLUMN_NAME_LONGITUDE = "longitude";
        public static final String COLUMN_NAME_ACCURACY = "accuracy";
        public static final String COLUMN_NAME_ALTITUDE = "altitude";
        public static final String COLUMN_NAME_VERTICAL_ACCURACY = "verticalAccuracy";
        public static final String COLUMN_NAME_TEMPERATURE = "temperature";
        public static final String COLUMN_NAME_HUMIDITY = "humidity";
        public static final String COLUMN_NAME_PRESSURE = "pressure";
        public static final String COLUMN_NAME_PM25 = "pm25";
        public static final String  COLUMN_NAME_PM10 = "pm10";
    }

    private static final String SQL_CREATE_ENTRIES =
            "CREATE TABLE " + DatalogEntry.TABLE_NAME + " (" +
                    DatalogEntry._ID + " INTEGER PRIMARY KEY," +
                    DatalogEntry.COLUMN_NAME_TIME + " REAL," +
                    DatalogEntry.COLUMN_NAME_LATITUDE + " REAL," +
                    DatalogEntry.COLUMN_NAME_LONGITUDE + " REAL," +
                    DatalogEntry.COLUMN_NAME_ACCURACY + " REAL," +
                    DatalogEntry.COLUMN_NAME_ALTITUDE + " REAL, " +
                    DatalogEntry.COLUMN_NAME_VERTICAL_ACCURACY + " REAL," +
                    DatalogEntry.COLUMN_NAME_TEMPERATURE + " REAL, " +
                    DatalogEntry.COLUMN_NAME_HUMIDITY + " REAL, " +
                    DatalogEntry.COLUMN_NAME_PRESSURE + " REAL, " +
                    DatalogEntry.COLUMN_NAME_PM25 + " INTEGER, " +
                    DatalogEntry.COLUMN_NAME_PM10 + " INTEGER)";

    private static final String SQL_DELETE_ENTRIES =
            "DROP TABLE IF EXISTS " + DatalogEntry.TABLE_NAME;

    private Context mContext;

    public DatalogDbHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
        mContext = context;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(SQL_CREATE_ENTRIES);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // For now, we don't care about older DB versions.  Just throw the data
        // away and start again.
        db.execSQL(SQL_DELETE_ENTRIES);
        setLastSyncId(mContext, -1);
        onCreate(db);
    }

    public static long getLastSyncId(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(SYNC_SHARED_PREFERENCES_KEY,
                MODE_PRIVATE);
        return prefs.getLong(PREF_LAST_SYNCED_ID, -1);
    }

    public static void setLastSyncId(Context context, long id) {
        SharedPreferences prefs = context.getSharedPreferences(SYNC_SHARED_PREFERENCES_KEY,
                MODE_PRIVATE);
        prefs.edit().putLong(PREF_LAST_SYNCED_ID, id).apply();
    }

    public static long log(SQLiteDatabase db, double time, double latitude, double longitude,
            float accuracy, double altitude, float verticalAccuracy, Float temperature,
                           Float humidity, Float pressure, Integer pm25, Integer pm10) {
        ContentValues values = new ContentValues();
        values.put(DatalogEntry.COLUMN_NAME_TIME, time);
        values.put(DatalogEntry.COLUMN_NAME_LATITUDE, latitude);
        values.put(DatalogEntry.COLUMN_NAME_LONGITUDE, longitude);
        values.put(DatalogEntry.COLUMN_NAME_ACCURACY, accuracy);
        values.put(DatalogEntry.COLUMN_NAME_ALTITUDE, altitude);
        values.put(DatalogEntry.COLUMN_NAME_VERTICAL_ACCURACY, verticalAccuracy);
        values.put(DatalogEntry.COLUMN_NAME_TEMPERATURE, temperature);
        values.put(DatalogEntry.COLUMN_NAME_HUMIDITY, humidity);
        values.put(DatalogEntry.COLUMN_NAME_PRESSURE, pressure);
        values.put(DatalogEntry.COLUMN_NAME_PM25, pm25);
        values.put(DatalogEntry.COLUMN_NAME_PM10, pm10);
        return db.insert(DatalogEntry.TABLE_NAME, null, values);
    }

    public static void clearEntries(SQLiteDatabase db) {
        db.execSQL(SQL_DELETE_ENTRIES);
        db.execSQL(SQL_CREATE_ENTRIES);
    }
 }
