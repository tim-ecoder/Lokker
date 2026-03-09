package com.lokker.app.data.db;

import androidx.room.Entity;
import androidx.room.PrimaryKey;
import androidx.room.TypeConverters;
import java.util.List;

@Entity(tableName = "hotkey_map")
public class HotkeyMap {
    @PrimaryKey
    public int id = 1;
    @TypeConverters(Converters.class)
    public List<Integer> lokkerHotkey;
}
