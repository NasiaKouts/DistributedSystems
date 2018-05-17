package com.distributedsystems.recommendationsystemclient.Fragments;

import android.content.Context;
import android.content.Intent;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.AsyncTaskLoader;
import android.support.v4.content.Loader;
import android.support.v7.app.AlertDialog;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.distributedsystems.recommendationsystemclient.Models.Poi;
import com.distributedsystems.recommendationsystemclient.R;
import com.distributedsystems.recommendationsystemclient.Utils.NetworkUtils;

import java.util.ArrayList;

import butterknife.BindView;
import butterknife.OnClick;

public class SearchUserFragment extends BaseFragment {

    private static final int POIS_LOADER_ID = 100;

    @BindView(R.id.masterIp)
    public TextView mMasterIpTextView;

    @BindView(R.id.userId)
    public TextView mUserIdTextView;

    @BindView(R.id.numberOfPois)
    public TextView mNumberOfPoisTextView;

    @BindView(R.id.progressBarLayout)
    public LinearLayout mProgressBarLayout;

    @Override
    public int getResourceLayout() {
        return R.layout.fragment_search_user;
    }

    @OnClick(R.id.searchUserButton)
    public void searchButtonClick(View view){
        if(getActivity() == null) return;

        LoaderManager loaderManager = getActivity().getSupportLoaderManager();
        Loader<ArrayList<Poi>> poisLoader = loaderManager.getLoader(POIS_LOADER_ID);
        if (poisLoader == null) {
            loaderManager.initLoader(POIS_LOADER_ID, null, FetchPoisLoader);
        } else {
            loaderManager.restartLoader(POIS_LOADER_ID, null, FetchPoisLoader);
        }
    }

    public LoaderManager.LoaderCallbacks<ArrayList<Poi>> FetchPoisLoader =
        new LoaderManager.LoaderCallbacks<ArrayList<Poi>>() {
            @Override
            public Loader<ArrayList<Poi>> onCreateLoader(int id, Bundle args) {
                mProgressBarLayout.setVisibility(View.VISIBLE);
                return new FetchPoisAsyncTaskLoader(
                        getContext(),
                        mMasterIpTextView.getText().toString(),
                        Integer.parseInt(mUserIdTextView.getText().toString()),
                        Integer.parseInt(mNumberOfPoisTextView.getText().toString()));
            }

            @Override
            public void onLoadFinished(@NonNull Loader<ArrayList<Poi>> loader, ArrayList<Poi> data) {
                mProgressBarLayout.setVisibility(View.GONE);
                onResultsFetchedCallback.onResultsFetched(data);

//Intent intent = new Intent(this, DisplayMessageActivity.class);

                /*
                String resultPois;
                if(data == null) resultPois = "Unable to fetch pois. Please check your connectivity!";
                else {
                    StringBuilder stringBuilder = new StringBuilder();
                    data.forEach(poi -> stringBuilder.append(poi.getId()).append("\n"));
                    resultPois = stringBuilder.toString();
                }

                Log.e("Result:", resultPois);

                if(getContext() == null) return;


                AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
                TextView text = new TextView(getContext());
                text.setText(resultPois);
                builder.setView(text);

                builder.setPositiveButton("Ok", (dialog, which) -> {
                    AlertDialog alertDialog = (AlertDialog) dialog;
                    if(alertDialog == null) return;

                    alertDialog.cancel();
                });

                builder.show();
                */
            }

            @Override
            public void onLoaderReset(@NonNull Loader<ArrayList<Poi>> loader) {

            }
        };

    private static class FetchPoisAsyncTaskLoader extends AsyncTaskLoader<ArrayList<Poi>> {
        private String fullIp;
        private int userId;
        private int numberOfPois;

        private ArrayList<Poi> pois;

        FetchPoisAsyncTaskLoader(Context context, String fullIp, int userId, int numberOfPois){
            super(context);
            this.fullIp = fullIp;
            this.userId = userId;
            this.numberOfPois = numberOfPois;
        }

        @Override
        protected void onStartLoading() {
            if(pois == null) {
                forceLoad();
            }
            else {
                deliverResult(pois);
            }
        }

        @Nullable
        @Override
        public ArrayList<Poi> loadInBackground() {
            String[] tokens = fullIp.split(":");
            if(tokens.length != 2) return null;

            int port = Integer.parseInt(tokens[1]);

            NetworkUtils networkService = new NetworkUtils(tokens[0], port);
            pois = networkService.GetRecommendationPois(userId, numberOfPois);

            return pois;
        }

        @Override
        public void deliverResult(@Nullable ArrayList<Poi> data) {
            pois = data;
            super.deliverResult(data);
        }
    }

    /* Establish communication between this fragment and the wrapper activity
     * In order to inform when the results have been calculated
     * and let activity handle fragment transactions
     */
    OnResultsFetchedListener onResultsFetchedCallback;

    public interface OnResultsFetchedListener {
        void onResultsFetched(ArrayList<Poi> pois);
    }
    // --------------------------------------------------------------------

    // Override onAttach to make sure that the container activity has implemented the callback
    @Override
    public void onAttach(Context context) {
        super.onAttach(context);

        // This makes sure that the host activity has implemented the callback interface
        // If not, it throws an exception
        try {
            onResultsFetchedCallback = (OnResultsFetchedListener) context;
        } catch (ClassCastException e) {
            throw new ClassCastException(context.toString()
                    + " must implement OnResultsFetchedListener");
        }
    }
}
