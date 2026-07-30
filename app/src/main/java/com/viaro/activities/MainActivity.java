package com.viaro.activities;

import android.content.Intent;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import com.vineyard.viaro.app.R;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 1. Driver Mode Option
        findViewById(R.id.btn_driver_mode).setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, DriverSetupActivity.class);
            startActivity(intent);
        });

        // 2. Passenger / User Mode Option
        findViewById(R.id.btn_user_mode).setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, UserMenuActivity.class);
            startActivity(intent);
        });

        // 3. NEW: Map Assistance Option (Gemini Live Map AI)
        findViewById(R.id.btn_map_assistance).setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, MapAssistanceActivity.class);
            startActivity(intent);
        });
    }
}