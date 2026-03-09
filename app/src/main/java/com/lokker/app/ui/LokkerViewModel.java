package com.lokker.app.ui;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;

import com.lokker.app.data.db.LokkerApp;
import com.lokker.app.data.db.LokkerDatabase;
import com.lokker.app.data.db.LokkerAppDao;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class LokkerViewModel extends AndroidViewModel {

    private final LokkerAppDao dao;
    private final LiveData<List<LokkerApp>> allApps;
    private final MutableLiveData<String> searchQuery = new MutableLiveData<>("");
    private final LiveData<List<LokkerApp>> filteredApps;

    public LokkerViewModel(@NonNull Application application) {
        super(application);
        dao = LokkerDatabase.getInstance(application).lokkerAppDao();
        allApps = dao.getAllLive();

        filteredApps = Transformations.switchMap(searchQuery, query ->
                Transformations.map(allApps, apps -> filterApps(apps, query))
        );
    }

    public LiveData<List<LokkerApp>> getAllApps() {
        return allApps;
    }

    public LiveData<List<LokkerApp>> getFilteredApps() {
        return filteredApps;
    }

    public MutableLiveData<String> getSearchQuery() {
        return searchQuery;
    }

    public void setSearchQuery(String query) {
        searchQuery.setValue(query);
    }

    @NonNull
    private List<LokkerApp> filterApps(List<LokkerApp> apps, String query) {
        if (apps == null) {
            return new ArrayList<>();
        }
        if (query == null || query.trim().isEmpty()) {
            return new ArrayList<>(apps);
        }
        String lower = query.toLowerCase(Locale.ROOT).trim();
        List<LokkerApp> result = new ArrayList<>();
        for (LokkerApp app : apps) {
            boolean matchesLabel = app.appLabel != null
                    && app.appLabel.toLowerCase(Locale.ROOT).contains(lower);
            boolean matchesPkg = app.packageName.toLowerCase(Locale.ROOT).contains(lower);
            if (matchesLabel || matchesPkg) {
                result.add(app);
            }
        }
        return result;
    }
}
