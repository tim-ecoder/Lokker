package com.lokker.app.data.db;

import androidx.room.TypeConverter;
import org.json.JSONArray;
import org.json.JSONException;
import java.util.ArrayList;
import java.util.List;

public class Converters {

    @TypeConverter
    public static String fromIntList(List<Integer> list) {
        if (list == null) {
            return null;
        }
        JSONArray jsonArray = new JSONArray();
        for (Integer value : list) {
            jsonArray.put(value);
        }
        return jsonArray.toString();
    }

    @TypeConverter
    public static List<Integer> toIntList(String json) {
        if (json == null) {
            return null;
        }
        List<Integer> list = new ArrayList<>();
        try {
            JSONArray jsonArray = new JSONArray(json);
            for (int i = 0; i < jsonArray.length(); i++) {
                list.add(jsonArray.getInt(i));
            }
        } catch (JSONException e) {
            return null;
        }
        return list;
    }
}
