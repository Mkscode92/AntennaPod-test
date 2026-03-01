package de.danoeh.antennapod.ui.discovery;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.GridView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import de.danoeh.antennapod.ui.discovery.databinding.QuickFeedDiscoveryBinding;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static android.content.Context.MODE_PRIVATE;

public class QuickFeedDiscoveryFragment extends Fragment implements AdapterView.OnItemClickListener {
    private static final String TAG = "QuickFeedDiscoveryFrag";
    private static final int NUM_SUGGESTIONS = 12;

    private Disposable disposable;
    private FeedDiscoveryAdapter adapter;
    private QuickFeedDiscoveryBinding viewBinding;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        viewBinding = QuickFeedDiscoveryBinding.inflate(inflater, container, false);
        viewBinding.discoverGrid.setOnItemClickListener(this);
        adapter = new FeedDiscoveryAdapter(getActivity());
        viewBinding.discoverGrid.setAdapter(adapter);
        return viewBinding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        loadToplist();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (disposable != null) {
            disposable.dispose();
        }
    }

    private void loadToplist() {
        viewBinding.errorContainer.setVisibility(View.GONE);
        viewBinding.errorRetryButton.setVisibility(View.INVISIBLE);
        viewBinding.errorRetryButton.setText(R.string.retry_label);
        viewBinding.poweredByLabel.setVisibility(View.VISIBLE);

        ItunesTopListLoader loader = new ItunesTopListLoader(getContext());
        SharedPreferences prefs = getActivity().getSharedPreferences(ItunesTopListLoader.PREFS, MODE_PRIVATE);
        String countryCode = prefs.getString(ItunesTopListLoader.PREF_KEY_COUNTRY_CODE,
                Locale.getDefault().getCountry());
        if (prefs.getBoolean(ItunesTopListLoader.PREF_KEY_HIDDEN_DISCOVERY_COUNTRY, false)) {
            viewBinding.errorLabel.setText(R.string.discover_is_hidden);
            viewBinding.errorContainer.setVisibility(View.VISIBLE);
            viewBinding.discoverGrid.setVisibility(View.GONE);
            viewBinding.errorRetryButton.setVisibility(View.GONE);
            viewBinding.poweredByLabel.setVisibility(View.GONE);
            return;
        }
        //noinspection ConstantConditions
        if (BuildConfig.FLAVOR.equals("free") && prefs.getBoolean(ItunesTopListLoader.PREF_KEY_NEEDS_CONFIRM, false)) {
            viewBinding.errorLabel.setText("");
            viewBinding.errorContainer.setVisibility(View.VISIBLE);
            viewBinding.discoverGrid.setVisibility(View.VISIBLE);
            viewBinding.errorRetryButton.setVisibility(View.VISIBLE);
            viewBinding.errorRetryButton.setText(R.string.discover_confirm);
            viewBinding.poweredByLabel.setVisibility(View.VISIBLE);
            viewBinding.errorRetryButton.setOnClickListener(v -> {
                prefs.edit().putBoolean(ItunesTopListLoader.PREF_KEY_NEEDS_CONFIRM, false).apply();
                loadToplist();
            });
            return;
        }

        disposable = Observable.fromCallable(() ->
                        loader.loadToplist(countryCode, NUM_SUGGESTIONS))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        podcasts -> {
                            viewBinding.errorContainer.setVisibility(View.GONE);
                            viewBinding.discoverGrid.setVisibility(View.VISIBLE);
                            adapter.clear();
                            adapter.addAll(podcasts);
                            adapter.notifyDataSetChanged();
                        }, error -> {
                            Log.e(TAG, Log.getStackTraceString(error));
                            viewBinding.errorLabel.setText(error.getMessage());
                            viewBinding.errorContainer.setVisibility(View.VISIBLE);
                            viewBinding.discoverGrid.setVisibility(View.GONE);
                            viewBinding.errorRetryButton.setVisibility(View.VISIBLE);
                            viewBinding.errorRetryButton.setOnClickListener(v -> loadToplist());
                        });
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        PodcastSearchResult podcast = adapter.getItem(position);
        if (podcast == null || podcast.feedUrl == null) {
            return;
        }
        ((OnDiscoveryDefaultAddListener) getActivity()).onDefaultAdd(podcast.feedUrl);
    }

    public interface OnDiscoveryDefaultAddListener {
        void onDefaultAdd(String feedUrl);
    }
}
