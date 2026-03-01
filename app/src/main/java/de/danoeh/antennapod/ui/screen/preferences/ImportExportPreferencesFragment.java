package de.danoeh.antennapod.ui.screen.preferences;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ProgressBar;
import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import de.danoeh.antennapod.R;
import de.danoeh.antennapod.activity.MainActivity;
import de.danoeh.antennapod.storage.importexport.DatabaseExporter;
import de.danoeh.antennapod.storage.importexport.DatabaseImporter;
import de.danoeh.antennapod.storage.importexport.OpmlExporter;
import de.danoeh.antennapod.storage.importexport.OpmlImporter;
import io.reactivex.Completable;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;

public class ImportExportPreferencesFragment extends PreferenceFragmentCompat {
    private static final String TAG = "ImportExportPrefFragment";
    private Disposable disposable;

    private final ActivityResultLauncher<String> chooseOpmlExportPathLauncher =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("text/x-opml"),
                    uri -> exportWithUri(uri, ExportType.OPML));
    private final ActivityResultLauncher<String> chooseHtmlExportPathLauncher =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("text/html"),
                    uri -> exportWithUri(uri, ExportType.HTML));
    private final ActivityResultLauncher<String> chooseFavoritesExportPathLauncher =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("text/html"),
                    uri -> exportWithUri(uri, ExportType.FAVORITES));
    private final ActivityResultLauncher<String> chooseBackupPathLauncher =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("application/x-sqlite3"),
                    uri -> exportWithUri(uri, ExportType.DB));
    private final ActivityResultLauncher<String[]> chooseOpmlImportPathLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(),
                    uri -> importWithUri(uri, ExportType.OPML));
    private final ActivityResultLauncher<String[]> chooseDbImportPathLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(),
                    uri -> importWithUri(uri, ExportType.DB));

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        addPreferencesFromResource(R.xml.preferences_import_export);
        setupExportPreference();
    }

    private void setupExportPreference() {
        findPreference("pref_opml_export").setOnPreferenceClickListener(preference -> {
            chooseOpmlExportPathLauncher.launch("antennapod-feeds.opml");
            return true;
        });
        findPreference("pref_html_export").setOnPreferenceClickListener(preference -> {
            chooseHtmlExportPathLauncher.launch("antennapod-feeds.html");
            return true;
        });
        findPreference("pref_favorites_export").setOnPreferenceClickListener(preference -> {
            chooseFavoritesExportPathLauncher.launch("antennapod-favorites.html");
            return true;
        });
        findPreference("pref_backup_restore").setOnPreferenceClickListener(preference -> {
            chooseDbImportPathLauncher.launch(new String[]{"*/*"});
            return true;
        });
        findPreference("pref_database_export").setOnPreferenceClickListener(preference -> {
            chooseBackupPathLauncher.launch("antennapod-database.db");
            return true;
        });
        findPreference("pref_opml_import").setOnPreferenceClickListener(preference -> {
            chooseOpmlImportPathLauncher.launch(new String[]{"*/*"});
            return true;
        });
    }

    private void exportWithUri(Uri uri, ExportType exportType) {
        if (uri == null) {
            return;
        }
        ProgressBar progressBar = getView().findViewById(R.id.progressBar);
        if (progressBar != null) {
            progressBar.setVisibility(View.VISIBLE);
        }
        Observable<Uri> observable;
        if (exportType == ExportType.OPML) {
            observable = OpmlExporter.export(getContext(), uri);
        } else if (exportType == ExportType.HTML) {
            observable = OpmlExporter.exportHtml(getContext(), uri);
        } else if (exportType == ExportType.FAVORITES) {
            observable = OpmlExporter.exportFavorites(getContext(), uri);
        } else {
            observable = DatabaseExporter.exportTo(uri, getContext());
        }
        disposable = observable
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(outputFile -> {
                    if (progressBar != null) {
                        progressBar.setVisibility(View.GONE);
                    }
                    showExportSuccessSnackbar(outputFile, exportType.mimeType);
                }, this::showExportErrorDialog);
    }

    private void importWithUri(Uri uri, ExportType exportType) {
        if (uri == null) {
            return;
        }
        if (exportType == ExportType.DB) {
            new MaterialAlertDialogBuilder(getContext())
                    .setTitle(R.string.database_import_label)
                    .setMessage(R.string.database_import_warning_msg)
                    .setNegativeButton(R.string.no_label, null)
                    .setPositiveButton(R.string.confirm_label, (dialog, which) -> importDb(uri))
                    .show();
        } else {
            importOpml(uri);
        }
    }

    private void importOpml(Uri uri) {
        ProgressBar progressBar = getView().findViewById(R.id.progressBar);
        if (progressBar != null) {
            progressBar.setVisibility(View.VISIBLE);
        }
        disposable = OpmlImporter.importOpml(getContext(), uri)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(feedCount -> {
                    if (progressBar != null) {
                        progressBar.setVisibility(View.GONE);
                    }
                    showOpmlImportSuccessDialog(feedCount);
                }, this::showExportErrorDialog);
    }

    private void importDb(Uri uri) {
        ProgressBar progressBar = getView().findViewById(R.id.progressBar);
        if (progressBar != null) {
            progressBar.setVisibility(View.VISIBLE);
        }
        disposable = Completable.fromAction(() -> DatabaseImporter.importBackup(uri, getContext()))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(() -> {
                    if (progressBar != null) {
                        progressBar.setVisibility(View.GONE);
                    }
                    forceRestart();
                }, this::showExportErrorDialog);
    }

    private void forceRestart() {
        Intent intent = null;
        PackageManager pm = getContext().getPackageManager();
        if (pm != null) {
            intent = pm.getLaunchIntentForPackage(getContext().getPackageName());
        }
        if (intent == null) {
            // Fallback for Android 11+ where getLaunchIntentForPackage may return null
            intent = new Intent(getContext(), MainActivity.class);
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        getContext().getApplicationContext().startActivity(intent);
        Runtime.getRuntime().exit(0);
    }

    private void showExportSuccessSnackbar(Uri outputFile, String mimeType) {
        Snackbar snackbar = Snackbar.make(getView(), R.string.export_success_msg, Snackbar.LENGTH_LONG);
        snackbar.setAction(R.string.share_label, v -> {
            Intent intentShare = new Intent(Intent.ACTION_VIEW);
            intentShare.setDataAndType(outputFile, mimeType);
            intentShare.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intentShare, getString(R.string.share_label)));
        });
        snackbar.show();
    }

    private void showOpmlImportSuccessDialog(int feedCount) {
        AlertDialog.Builder dialog = new MaterialAlertDialogBuilder(getContext());
        dialog.setTitle(R.string.successful_import_label);
        dialog.setMessage(getContext().getResources().getQuantityString(
                R.plurals.opml_import_success_msg, feedCount, feedCount));
        dialog.setPositiveButton(android.R.string.ok, null);
        dialog.show();
    }

    private void showExportErrorDialog(final Throwable error) {
        Log.e(TAG, Log.getStackTraceString(error));
        ProgressBar progressBar = getView().findViewById(R.id.progressBar);
        if (progressBar != null) {
            progressBar.setVisibility(View.GONE);
        }
        AlertDialog.Builder dialog = new MaterialAlertDialogBuilder(getContext());
        dialog.setTitle(R.string.export_error_label);
        dialog.setMessage(error.getMessage());
        dialog.setPositiveButton(android.R.string.ok, null);
        dialog.show();
    }

    @Override
    public void onStop() {
        super.onStop();
        if (disposable != null) {
            disposable.dispose();
        }
    }

    private enum ExportType {
        OPML("text/x-opml"),
        HTML("text/html"),
        FAVORITES("text/html"),
        DB("application/x-sqlite3");

        public final String mimeType;

        ExportType(String mimeType) {
            this.mimeType = mimeType;
        }
    }
}
