package com.viaro.activities;

import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.vineyard.viaro.app.R;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;
import com.viaro.adapters.BusListAdapter;
import com.viaro.adapters.HorizontalNavAdapter;
import com.viaro.firebase.FirebaseHelper;
import com.viaro.fragments.MyBottomSheetFragment;
import com.viaro.models.BusModel;
import com.viaro.models.HorizontalNavItem;
import com.viaro.models.RouteResponse;
import com.viaro.network.ApiClient;
import com.viaro.utils.MapUtils;
import org.osmdroid.api.IMapController;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polyline;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class TrackBusActivity extends AppCompatActivity {

    private MapView mMapView;
    private IMapController mController;
    private FusedLocationProviderClient mFusedLocationClient;
    private GeoPoint mMyPosition = new GeoPoint(12.9716, 77.5946); // Default location fallback

    private EditText etSearch;
    private View btnClearSearch;
    private RecyclerView rvSuggestions, rvBottomNav;

    private DatabaseReference mBusesRef;
    private ValueEventListener mBusesListener;

    private final List<BusModel> mAllBuses = new ArrayList<>();
    private final List<BusModel> mFilteredBuses = new ArrayList<>();
    private final HashMap<String, Marker> mBusMarkers = new HashMap<>();
    private Polyline mActiveRoutePolyline;

    private BusListAdapter mSuggestionsAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_track_bus);

        mMapView = findViewById(R.id.map_track_bus);
        mMapView.setTileSource(TileSourceFactory.MAPNIK);
        mMapView.setMultiTouchControls(true);

        mController = mMapView.getController();
        mController.setZoom(15.0);
        mController.setCenter(mMyPosition);

        etSearch = findViewById(R.id.et_search_destination);
        btnClearSearch = findViewById(R.id.btn_clear_search);
        rvSuggestions = findViewById(R.id.rv_search_suggestions);
        rvBottomNav = findViewById(R.id.rv_bottom_nav);

        setupSearch();
        setupBottomNav();
        setupUserLocation();

        mBusesRef = FirebaseHelper.getBusesReference();
        setupFirebaseListener();
    }

    private void setupUserLocation() {
        mFusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        try {
            mFusedLocationClient.getLastLocation().addOnSuccessListener(location -> {
                if (location != null) {
                    mMyPosition = new GeoPoint(location.getLatitude(), location.getLongitude());
                    mController.setCenter(mMyPosition);
                }
            });
        } catch (SecurityException ignored) {
        }
    }

    private void setupSearch() {
        rvSuggestions.setLayoutManager(new LinearLayoutManager(this));
        mSuggestionsAdapter = new BusListAdapter(mFilteredBuses, this::onBusSelected);
        rvSuggestions.setAdapter(mSuggestionsAdapter);

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String query = s.toString().trim().toLowerCase();
                if (query.isEmpty()) {
                    btnClearSearch.setVisibility(View.GONE);
                    rvSuggestions.setVisibility(View.GONE);
                } else {
                    btnClearSearch.setVisibility(View.VISIBLE);
                    filterBuses(query);
                }
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        btnClearSearch.setOnClickListener(v -> {
            etSearch.setText("");
            rvSuggestions.setVisibility(View.GONE);
        });
    }

    private void filterBuses(String query) {
        mFilteredBuses.clear();
        for (BusModel bus : mAllBuses) {
            if (bus.getEndPoint().toLowerCase().contains(query) || bus.getName().toLowerCase().contains(query)) {
                mFilteredBuses.add(bus);
            }
        }
        if (!mFilteredBuses.isEmpty()) {
            rvSuggestions.setVisibility(View.VISIBLE);
            mSuggestionsAdapter.notifyDataSetChanged();
        } else {
            rvSuggestions.setVisibility(View.GONE);
        }
    }

    private void onBusSelected(BusModel bus) {
        rvSuggestions.setVisibility(View.GONE);
        etSearch.setText("");
        GeoPoint busPos = new GeoPoint(bus.getLatitude(), bus.getLongitude());
        mController.animateTo(busPos);
        showBusDetailsBottomSheet(bus);
    }

    private void setupBottomNav() {
        rvBottomNav.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        List<HorizontalNavItem> navItems = new ArrayList<>();
        navItems.add(new HorizontalNavItem(1, "Overview", R.drawable.ic_navigation));
        navItems.add(new HorizontalNavItem(2, "Buses", R.drawable.ic_bus));
        navItems.add(new HorizontalNavItem(3, "Near Me", R.drawable.ic_location));
        navItems.add(new HorizontalNavItem(4, "Profile", R.drawable.ic_person));

        HorizontalNavAdapter navAdapter = new HorizontalNavAdapter(navItems, item -> {
            if (item.getId() == 3) {
                mController.animateTo(mMyPosition);
            }
        });
        rvBottomNav.setAdapter(navAdapter);
    }

    private void setupFirebaseListener() {
        if (mBusesRef == null) return;

        mBusesListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                mAllBuses.clear();
                for (DataSnapshot postSnapshot : snapshot.getChildren()) {
                    BusModel bus = postSnapshot.getValue(BusModel.class);
                    if (bus != null) {
                        mAllBuses.add(bus);
                        updateBusMarker(bus);
                    }
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
            }
        };

        mBusesRef.addValueEventListener(mBusesListener);
    }

    private void updateBusMarker(BusModel bus) {
        GeoPoint busPos = new GeoPoint(bus.getLatitude(), bus.getLongitude());
        Marker marker = mBusMarkers.get(bus.getId());

        if (marker == null) {
            marker = new Marker(mMapView);
            marker.setIcon(getResources().getDrawable(R.drawable.ic_bus, null));
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
            marker.setTitle(bus.getName() + "\nTo: " + bus.getEndPoint());
            marker.setSubDescription("Speed: " + String.format("%.1f", bus.getSpeed()) + " km/h");
            
            marker.setOnMarkerClickListener((m, mv) -> {
                showBusDetailsBottomSheet(bus);
                return true;
            });

            mMapView.getOverlays().add(marker);
            mBusMarkers.put(bus.getId(), marker);
            marker.setPosition(busPos);
        } else {
            MapUtils.glideMarker(marker, busPos, 4500); // Live Glide pin over 4.5 seconds
            marker.setSubDescription("Speed: " + String.format("%.1f", bus.getSpeed()) + " km/h");
        }
        mMapView.invalidate();
    }

    private void showBusDetailsBottomSheet(BusModel bus) {
        String coordinates = bus.getLongitude() + "," + bus.getLatitude() + ";" + mMyPosition.getLongitude() + "," + mMyPosition.getLatitude();

        ApiClient.getOSRMApiService().getRoute(coordinates, "full", "polyline").enqueue(new Callback<RouteResponse>() {
            @Override
            public void onResponse(Call<RouteResponse> call, Response<RouteResponse> response) {
                String durationText = "--";
                if (response.isSuccessful() && response.body() != null && !response.body().getRoutes().isEmpty()) {
                    RouteResponse.Route route = response.body().getRoutes().get(0);
                    if (!route.getLegs().isEmpty()) {
                        double durationSeconds = route.getLegs().get(0).getDuration();
                        int durationMinutes = (int) Math.round(durationSeconds / 60.0);
                        durationText = durationMinutes + " min";
                    }
                    
                    final String encodedGeom = route.getGeometry();
                    
                    MyBottomSheetFragment.newInstance(
                        bus.getName(),
                        "Route: " + bus.getStartPoint() + " ➔ " + bus.getEndPoint(),
                        String.format("%.1f km/h", bus.getSpeed()),
                        durationText,
                        () -> drawRoute(encodedGeom)
                    ).show(getSupportFragmentManager(), "bus_details");
                } else {
                    MyBottomSheetFragment.newInstance(
                        bus.getName(),
                        "Route: " + bus.getStartPoint() + " ➔ " + bus.getEndPoint(),
                        String.format("%.1f km/h", bus.getSpeed()),
                        "--",
                        null
                    ).show(getSupportFragmentManager(), "bus_details");
                }
            }

            @Override
            public void onFailure(Call<RouteResponse> call, Throwable t) {
                MyBottomSheetFragment.newInstance(
                    bus.getName(),
                    "Route: " + bus.getStartPoint() + " ➔ " + bus.getEndPoint(),
                    String.format("%.1f km/h", bus.getSpeed()),
                    "--",
                    null
                ).show(getSupportFragmentManager(), "bus_details");
            }
        });
    }

    private void drawRoute(String encodedGeom) {
        if (encodedGeom == null) return;
        List<GeoPoint> routePoints = MapUtils.decodePolyline(encodedGeom);
        
        if (mActiveRoutePolyline != null) {
            mMapView.getOverlays().remove(mActiveRoutePolyline);
        }

        mActiveRoutePolyline = new Polyline();
        mActiveRoutePolyline.setPoints(routePoints);
        mActiveRoutePolyline.setColor(Color.BLUE);
        mActiveRoutePolyline.setWidth(10.0f);

        mMapView.getOverlays().add(mActiveRoutePolyline);
        mMapView.invalidate();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mMapView != null) mMapView.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mMapView != null) mMapView.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mBusesRef != null && mBusesListener != null) {
            mBusesRef.removeEventListener(mBusesListener);
        }
        if (mMapView != null) mMapView.onDetach();
    }
}
