package com.viaro.activities;

import android.content.Intent;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import com.example.R;

public class UserMenuActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_user_menu);

        findViewById(R.id.btn_track_bus).setOnClickListener(v -> {
            Intent intent = new Intent(UserMenuActivity.this, TrackBusActivity.class);
            startActivity(intent);
        });

        findViewById(R.id.btn_meet_friend).setOnClickListener(v -> {
            Intent intent = new Intent(UserMenuActivity.this, MeetSetupActivity.class);
            startActivity(intent);
        });
    }
}
