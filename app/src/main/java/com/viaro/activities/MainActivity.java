package com.viaro.activities;

import android.content.Intent;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import com.example.R;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        findViewById(R.id.btn_driver_mode).setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, DriverSetupActivity.class);
            startActivity(intent);
        });

        findViewById(R.id.btn_user_mode).setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, UserMenuActivity.class);
            startActivity(intent);
        });
    }
}
