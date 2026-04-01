package com.lokker.app.data.db;

import android.content.Context;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.TypeConverters;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(entities = {LokkerApp.class, HotkeyMap.class}, version = 2, exportSchema = false)
@TypeConverters(Converters.class)
public abstract class LokkerDatabase extends RoomDatabase {

    private static volatile LokkerDatabase INSTANCE;

    static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL(
                    "ALTER TABLE lokker_apps ADD COLUMN hotkeyUntilClosed INTEGER NOT NULL DEFAULT 0");
        }
    };

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
                    )
                            .addMigrations(MIGRATION_1_2)
                            .build();
                }
            }
        }
        return INSTANCE;
    }
}
