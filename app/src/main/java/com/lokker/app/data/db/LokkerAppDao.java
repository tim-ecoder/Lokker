package com.lokker.app.data.db;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import java.util.List;

@Dao
public interface LokkerAppDao {

    @Query("SELECT * FROM lokker_apps ORDER BY appLabel ASC")
    List<LokkerApp> getAll();

    @Query("SELECT * FROM lokker_apps ORDER BY appLabel ASC")
    LiveData<List<LokkerApp>> getAllLive();

    @Query("SELECT * FROM lokker_apps WHERE packageName = :packageName LIMIT 1")
    LokkerApp get(String packageName);

    @Query("SELECT EXISTS(SELECT 1 FROM lokker_apps WHERE packageName = :packageName)")
    boolean isManaged(String packageName);

    @Query("SELECT EXISTS(SELECT 1 FROM lokker_apps WHERE packageName = :packageName AND hidden = 1)")
    boolean isHidden(String packageName);

    @Query("SELECT * FROM lokker_apps WHERE hidden = 1 ORDER BY appLabel ASC")
    List<LokkerApp> getAllHidden();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(LokkerApp app);

    @Query("UPDATE lokker_apps SET hidden = :hidden WHERE packageName = :packageName")
    void setHidden(String packageName, boolean hidden);

    @Query("UPDATE lokker_apps SET hotkeySequence = :hotkey WHERE packageName = :packageName")
    void setHotkey(String packageName, String hotkey);

    @Query("UPDATE lokker_apps SET hotkeyUntilClosed = :value WHERE packageName = :packageName")
    void setHotkeyUntilClosed(String packageName, boolean value);

    @Query("DELETE FROM lokker_apps WHERE packageName = :packageName")
    void delete(String packageName);

    @Query("DELETE FROM lokker_apps")
    void deleteAll();
}
