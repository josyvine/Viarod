package com.viaro.fragments;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.vineyard.viaro.app.R;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

public class MyBottomSheetFragment extends BottomSheetDialogFragment {

    private String busName = "";
    private String busRoute = "";
    private String speed = "0 km/h";
    private String eta = "-- min";
    private Runnable onTrackLiveClick = null;

    public static MyBottomSheetFragment newInstance(String busName, String busRoute, String speed, String eta, Runnable onTrackLiveClick) {
        MyBottomSheetFragment fragment = new MyBottomSheetFragment();
        fragment.busName = busName;
        fragment.busRoute = busRoute;
        fragment.speed = speed;
        fragment.eta = eta;
        fragment.onTrackLiveClick = onTrackLiveClick;
        return fragment;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_bus_details, container, false);

        TextView tvName = view.findViewById(R.id.tv_bs_bus_name);
        TextView tvRoute = view.findViewById(R.id.tv_bs_bus_route);
        TextView tvSpeed = view.findViewById(R.id.tv_bs_speed);
        TextView tvEta = view.findViewById(R.id.tv_bs_eta);
        Button btnTrack = view.findViewById(R.id.btn_bs_track_live);

        tvName.setText(busName);
        tvRoute.setText(busRoute);
        tvSpeed.setText(speed);
        tvEta.setText(eta);

        btnTrack.setOnClickListener(v -> {
            if (onTrackLiveClick != null) {
                onTrackLiveClick.run();
            }
            dismiss();
        });

        return view;
    }
}
