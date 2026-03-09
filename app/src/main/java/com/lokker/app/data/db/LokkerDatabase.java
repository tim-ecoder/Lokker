package com.lokker.app.data.db;

import android.content.Context;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.TypeConverters;

@Database(entities = {LokkerApp.class, HotkeyMap.class}, version = 1, exportSchema = false)
@TypeConverters(Converters.class)
public abstract class LokkerDatabase extends RoomDatabase {

    private static volatile LokkerDatabase INSTANCE;

    public abstract LokkerAppDao lokkerAppDao();
    public abstract HotkeyMapDao hotkeyMapDao();

    public static LokkerDatabase getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (LokkerDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                            context.getApplicationContext(),
                            LokkerDatabase.class,
                            "lokker_database"
                    ).build();
                }
            }
        }
        return INSTANCE;
    }
}
