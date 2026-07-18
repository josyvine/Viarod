package com.viaro.activities;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.example.R;
import com.viaro.utils.RoomCodeGenerator;

public class MeetSetupActivity extends AppCompatActivity {

    private LinearLayout layoutCode;
    private TextView tvRoomCode;
    private EditText etRoomCode;

    private String currentRoomCode = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_meet_setup);

        layoutCode = findViewById(R.id.layout_generated_code);
        tvRoomCode = findViewById(R.id.tv_room_code);
        etRoomCode = findViewById(R.id.et_room_code);

        findViewById(R.id.btn_create_room).setOnClickListener(v -> generateRoom());
        findViewById(R.id.btn_share_code).setOnClickListener(v -> shareCode());
        findViewById(R.id.btn_join_room).setOnClickListener(v -> joinRoom());
    }

    private void generateRoom() {
        currentRoomCode = RoomCodeGenerator.generateRoomCode();
        tvRoomCode.setText(currentRoomCode.substring(0, 3) + " - " + currentRoomCode.substring(3));
        layoutCode.setVisibility(View.VISIBLE);

        // Auto launch room in 1.5 seconds for testing or direct access
        layoutCode.postDelayed(() -> launchMeetMap(currentRoomCode, "creator"), 2000);
    }

    private void shareCode() {
        if (currentRoomCode == null) return;
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_TEXT, "Join my viaro meetup room! Code: " + currentRoomCode);
        startActivity(Intent.createChooser(shareIntent, "Share via"));
    }

    private void joinRoom() {
        String code = etRoomCode.getText().toString().trim();
        if (code.length() != 6) {
            Toast.makeText(this, "Please enter a valid 6-digit code.", Toast.LENGTH_SHORT).show();
            return;
        }
        launchMeetMap(code, "joiner");
    }

    private void launchMeetMap(String roomCode, String role) {
        Intent intent = new Intent(MeetSetupActivity.this, MeetMapActivity.class);
        intent.putExtra("room_code", roomCode);
        intent.putExtra("role", role);
        startActivity(intent);
        finish();
    }
}
