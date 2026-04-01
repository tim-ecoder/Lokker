package com.lokker.app.data.db;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;
import androidx.room.TypeConverters;
import java.util.List;

@Entity(tableName = "lokker_apps")
public class LokkerApp {
    @PrimaryKey @NonNull
    public String packageName;
    public String appLabel;
    @TypeConverters(Converters.class)
    public List<Integer> hotkeySequence;
    public boolean hidden;
    public long addedAt;
    public boolean hotkeyUntilClosed;

    public LokkerApp(@NonNull String packageName, String appLabel, List<Integer> hotkeySequence, boolean hidden, long addedAt) {
        this.packageName = packageName;
        this.appLabel = appLabel;
        this.hotkeySequence = hotkeySequence;
        this.hidden = hidden;
        this.addedAt = addedAt;
    }
}
