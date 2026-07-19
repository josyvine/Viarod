package com.viaro.activities;

import android.content.Intent;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.vineyard.viaro.app.R;
import com.viaro.utils.PermissionHelper;
import java.util.UUID;

public class DriverSetupActivity extends AppCompatActivity {

    private EditText etBusName, etStartPoint, etEndPoint;
    private static final int BACKGROUND_LOCATION_REQUEST_CODE = 2002;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_driver_setup);

        etBusName = findViewById(R.id.et_bus_name);
        etStartPoint = findViewById(R.id.et_start_point);
        etEndPoint = findViewById(R.id.et_end_point);

        findViewById(R.id.btn_start_broadcasting).setOnClickListener(v -> validateAndProceed());
    }

    private void validateAndProceed() {
        String busName = etBusName.getText().toString().trim();
        String startPoint = etStartPoint.getText().toString().trim();
        String endPoint = etEndPoint.getText().toString().trim();

        if (busName.isEmpty() || startPoint.isEmpty() || endPoint.isEmpty()) {
            Toast.makeText(this, "Please fill in all details.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!PermissionHelper.hasBackgroundLocationPermission(this)) {
            PermissionHelper.requestBackgroundLocationPermission(this, BACKGROUND_LOCATION_REQUEST_CODE);
        } else {
            launchDriverMap(busName, startPoint, endPoint);
        }
    }

    private void launchDriverMap(String busName, String startPoint, String endPoint) {
        String busId = UUID.randomUUID().toString().substring(0, 8); // Stable UUID snippet

        Intent intent = new Intent(DriverSetupActivity.this, DriverMapActivity.class);
        intent.putExtra("bus_id", busId);
        intent.putExtra("bus_name", busName);
        intent.putExtra("start_point", startPoint);
        intent.putExtra("end_point", endPoint);
        startActivity(intent);
        finish();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == BACKGROUND_LOCATION_REQUEST_CODE) {
            // Proceed anyway, standard location services will prompt at launch
            validateAndProceed();
        }
    }
}
