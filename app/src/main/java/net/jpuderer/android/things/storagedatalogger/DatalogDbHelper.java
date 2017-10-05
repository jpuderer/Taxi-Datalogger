package net.jpuderer.android.things.storagedatalogger;

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.SystemClock;
import android.provider.BaseColumns;

// TODO: Look at Contacts provider to inform me about how I should structure this
public class DatalogDbHelper extends SQLiteOpenHelper {
    public static final int DATABASE_VERSION = 1;
    public static final String DATABASE_NAME = "Datalog.db";

    public static final String TABLE_NAME = "datalog";

    private static class DatalogEntry implements BaseColumns {
        static final String TABLE_NAME = "datalog";
        public static final String COLUMN_NAME_CURRENT_TIME = "currentTime";
        public static final String COLUMN_NAME_ELAPSED_REALTIME = "elapsedRealtime";
        public static final String COLUMN_NAME_TEMPERATURE = "temperature";
        public static final String COLUMN_NAME_HUMIDITY = "humidity";
    }

    private static final String SQL_CREATE_ENTRIES =
            "CREATE TABLE " + DatalogEntry.TABLE_NAME + " (" +
                    DatalogEntry._ID + " INTEGER PRIMARY KEY," +
                    DatalogEntry.COLUMN_NAME_CURRENT_TIME + " INTEGER," +
                    DatalogEntry.COLUMN_NAME_ELAPSED_REALTIME + " INTEGER," +
                    DatalogEntry.COLUMN_NAME_TEMPERATURE + " REAL," +
                    DatalogEntry.COLUMN_NAME_HUMIDITY + " REAL)";

    private static final String SQL_DELETE_ENTRIES =
            "DROP TABLE IF EXISTS " + DatalogEntry.TABLE_NAME;

    public DatalogDbHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(SQL_CREATE_ENTRIES);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // For now, we don't care about older DB version.  Just throw the data
        // away and start again.
        db.execSQL(SQL_DELETE_ENTRIES);
        onCreate(db);
    }

    public static long log(SQLiteDatabase db, float temperature, float humidity) {
        ContentValues values = new ContentValues();
        values.put(DatalogEntry.COLUMN_NAME_CURRENT_TIME, System.currentTimeMillis());
        values.put(DatalogEntry.COLUMN_NAME_ELAPSED_REALTIME, SystemClock.elapsedRealtime());
        values.put(DatalogEntry.COLUMN_NAME_TEMPERATURE, temperature);
        values.put(DatalogEntry.COLUMN_NAME_HUMIDITY, humidity);
        return db.insert(DatalogEntry.TABLE_NAME, null, values);
    }
}
