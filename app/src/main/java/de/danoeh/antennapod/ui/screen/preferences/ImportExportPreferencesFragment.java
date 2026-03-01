package de.danoeh.antennapod.ui.screen.preferences;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.documentfile.provider.DocumentFile;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import com.google.android.material.snackbar.Snackbar;
import de.danoeh.antennapod.R;
import de.danoeh.antennapod.storage.exporters.AutomaticDatabaseExporter;
import de.danoeh.antennapod.ui.preferences.screen.ImportExportPreferencesHelper;
import io.reactivex.Completable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class ImportExportPreferencesFragment extends PreferenceFragmentCompat {
    private static final String TAG = "ImportExportPrefFrag";

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        addPreferencesFromResource(R.xml.preferences_import_export);
        setupPreferenceListeners();
    }

    private void setupPreferenceListeners() {
        ImportExportPreferencesHelper.setupPreferences(this);
    }

    private void forceRestart() {
        PackageManager pm = getContext().getPackageManager();
        Intent intent = pm.getLaunchIntentForPackage(getContext().getPackageName());
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().getApplicationContext().startActivity(intent);
        }
        Runtime.getRuntime().exit(0);
    }
}
