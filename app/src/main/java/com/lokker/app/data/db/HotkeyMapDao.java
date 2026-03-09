package com.lokker.app.data.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

@Dao
public interface HotkeyMapDao {

    @Query("SELECT * FROM hotkey_map WHERE id = 1 LIMIT 1")
    HotkeyMap get();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertOrUpdate(HotkeyMap hotkeyMap);
}
