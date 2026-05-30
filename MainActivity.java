package com.accidentdetector;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.AnimatorSet;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.location.Location;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.ContactsContract;
import android.telephony.SmsManager;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.tasks.OnSuccessListener;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    // ─── Constants ──────────────────────────────────────────────────
    private static final String ESP_URL          = "http://192.168.4.1/status";
    private static final int    POLL_INTERVAL_MS = 2000;
    private static final String PREFS_NAME       = "AccidentDetectorPrefs";
    private static final String PREF_CONTACT_NAME   = "contact_name";
    private static final String PREF_CONTACT_NUMBER = "contact_number";

    // Permission request codes
    private static final int REQ_PERMISSIONS  = 100;
    private static final int REQ_CONTACT_PICK = 200;

    private static final String[] ALL_PERMISSIONS = {
        Manifest.permission.CALL_PHONE,
        Manifest.permission.SEND_SMS,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    };

    // ─── UI References ───────────────────────────────────────────────
    private CardView  cardStatus;
    private TextView  tvStatus, tvStatusIcon, tvStatusSub;
    private TextView  tvConnStatus, tvLastUpdate;
    private TextView  tvContactName, tvContactNumber;
    private TextView  tvLocation;
    private Button    btnSelectContact, btnManualCall, btnManualSms;
    private View      viewConnDot;

    // ─── State ───────────────────────────────────────────────────────
    private String currentState = "normal";
    private String prevState    = "";
    private double  latitude    = 0.0;
    private double  longitude   = 0.0;
    private boolean alertActionFired = false;

    private SharedPreferences prefs;
    private FusedLocationProviderClient fusedLocation;
    private Handler  pollHandler;
    private Runnable pollRunnable;
    private ExecutorService executor;
    private Animation blinkAnim;

    // ─── Lifecycle ───────────────────────────────────────────────────
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs         = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        fusedLocation = LocationServices.getFusedLocationProviderClient(this);
        executor      = Executors.newSingleThreadExecutor();
        pollHandler   = new Handler(Looper.getMainLooper());

        bindViews();
        setupButtonListeners();
        setupBlinkAnimation();
        loadSavedContact();
        requestAllPermissions();
        startPolling();
        fetchLocation();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopPolling();
        if (executor != null) executor.shutdown();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CONTACT_PICK && resultCode == RESULT_OK && data != null) {
            handleContactResult(data.getData());
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSIONS) {
            fetchLocation();
        }
    }

    // ─── View Binding ────────────────────────────────────────────────
    private void bindViews() {
        cardStatus       = findViewById(R.id.cardStatus);
        tvStatus         = findViewById(R.id.tvStatus);
        tvStatusIcon     = findViewById(R.id.tvStatusIcon);
        tvStatusSub      = findViewById(R.id.tvStatusSub);
        tvConnStatus     = findViewById(R.id.tvConnStatus);
        tvLastUpdate     = findViewById(R.id.tvLastUpdate);
        tvContactName    = findViewById(R.id.tvContactName);
        tvContactNumber  = findViewById(R.id.tvContactNumber);
        tvLocation       = findViewById(R.id.tvLocation);
        btnSelectContact = findViewById(R.id.btnSelectContact);
        btnManualCall    = findViewById(R.id.btnManualCall);
        btnManualSms     = findViewById(R.id.btnManualSms);
        viewConnDot      = findViewById(R.id.viewConnDot);
    }

    // ─── Button Listeners ────────────────────────────────────────────
    private void setupButtonListeners() {
        btnSelectContact.setOnClickListener(v -> openContactPicker());
        btnManualCall.setOnClickListener(v -> {
            if (ensureContactSelected()) makeCall();
        });
        btnManualSms.setOnClickListener(v -> {
            if (ensureContactSelected()) sendSmsAlert();
        });
    }

    // ─── Blink Animation ─────────────────────────────────────────────
    private void setupBlinkAnimation() {
        blinkAnim = new AlphaAnimation(1.0f, 0.15f);
        blinkAnim.setDuration(600);
        blinkAnim.setRepeatCount(Animation.INFINITE);
        blinkAnim.setRepeatMode(Animation.REVERSE);
    }

    // ─── Polling ESP ─────────────────────────────────────────────────
    private void startPolling() {
        pollRunnable = new Runnable() {
            @Override
            public void run() {
                fetchEspStatus();
                pollHandler.postDelayed(this, POLL_INTERVAL_MS);
            }
        };
        pollHandler.post(pollRunnable);
    }

    private void stopPolling() {
        if (pollHandler != null && pollRunnable != null) {
            pollHandler.removeCallbacks(pollRunnable);
        }
    }

    private void fetchEspStatus() {
        executor.execute(() -> {
            try {
                URL url = new URL(ESP_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(1500);
                conn.setReadTimeout(1500);
                conn.connect();

                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();
                conn.disconnect();

                JSONObject json = new JSONObject(sb.toString());
                String state = json.getString("state"); // normal / alert / calling / stopped

                runOnUiThread(() -> {
                    updateConnectionOk();
                    handleStateChange(state);
                });

            } catch (Exception e) {
                runOnUiThread(() -> updateConnectionFailed());
            }
        });
    }

    // ─── State Machine ───────────────────────────────────────────────
    private void handleStateChange(String newState) {
        currentState = newState;

        // Update timestamp
        String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        tvLastUpdate.setText("Updated: " + time);

        // Fire one-shot actions when entering "calling"
        if ("calling".equals(newState) && !"calling".equals(prevState)) {
            alertActionFired = false;
        }
        if ("calling".equals(newState) && !alertActionFired && hasContactSelected()) {
            alertActionFired = true;
            triggerEmergencyActions();
        }

        // Vibrate on new alert / calling
        if (("alert".equals(newState) || "calling".equals(newState))
                && !newState.equals(prevState)) {
            vibrateAlert();
        }

        prevState = newState;
        updateStatusUI(newState);
    }

    @SuppressLint("SetTextI18n")
    private void updateStatusUI(String state) {
        // Stop any existing blink
        tvStatus.clearAnimation();

        switch (state) {
            case "normal":
                cardStatus.setCardBackgroundColor(getColor(R.color.card_normal));
                tvStatusIcon.setText("✅");
                tvStatus.setText("SYSTEM NORMAL");
                tvStatus.setTextColor(getColor(R.color.status_normal));
                tvStatusSub.setText("Monitoring active • Connected to ESP");
                break;

            case "alert":
                cardStatus.setCardBackgroundColor(getColor(R.color.card_alert));
                tvStatusIcon.setText("⚠️");
                tvStatus.setText("ACCIDENT DETECTED");
                tvStatus.setTextColor(getColor(R.color.status_alert));
                tvStatusSub.setText("Impact detected! Preparing emergency response...");
                break;

            case "calling":
                cardStatus.setCardBackgroundColor(getColor(R.color.card_calling));
                tvStatusIcon.setText("🚨");
                tvStatus.setText("CALLING EMERGENCY");
                tvStatus.setTextColor(getColor(R.color.status_calling));
                tvStatusSub.setText("Emergency contact being notified!");
                tvStatus.startAnimation(blinkAnim);
                break;

            case "stopped":
                cardStatus.setCardBackgroundColor(getColor(R.color.card_stopped));
                tvStatusIcon.setText("🟡");
                tvStatus.setText("ALERT CANCELLED");
                tvStatus.setTextColor(getColor(R.color.status_stopped));
                tvStatusSub.setText("Alert was manually cancelled on device");
                alertActionFired = false;
                break;
        }
    }

    // ─── Connection Indicators ───────────────────────────────────────
    private void updateConnectionOk() {
        viewConnDot.setBackgroundResource(R.drawable.circle_green);
        tvConnStatus.setText("  Connected to 192.168.4.1");
    }

    private void updateConnectionFailed() {
        viewConnDot.setBackgroundResource(R.drawable.circle_red);
        tvConnStatus.setText("  Cannot reach ESP device");
        tvLastUpdate.setText("Retrying...");
        // Show disconnected UI
        cardStatus.setCardBackgroundColor(getColor(R.color.card_dark));
        tvStatusIcon.setText("📡");
        tvStatus.setText("DISCONNECTED");
        tvStatus.setTextColor(0xFF888888);
        tvStatus.clearAnimation();
        tvStatusSub.setText("Make sure your phone is on ESP WiFi network");
    }

    // ─── Emergency Actions ───────────────────────────────────────────
    private void triggerEmergencyActions() {
        fetchLocation(); // refresh location first
        // Small delay to let GPS update
        pollHandler.postDelayed(() -> {
            sendSmsAlert();
            pollHandler.postDelayed(this::makeCall, 2000);
        }, 1500);
    }

    private void makeCall() {
        String number = getSavedNumber();
        if (number.isEmpty()) return;

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
                != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Call permission not granted", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent callIntent = new Intent(Intent.ACTION_CALL);
        callIntent.setData(Uri.parse("tel:" + number));
        startActivity(callIntent);
    }

    private void sendSmsAlert() {
        String number = getSavedNumber();
        if (number.isEmpty()) return;

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
                != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "SMS permission not granted", Toast.LENGTH_SHORT).show();
            return;
        }

        String locUrl = (latitude != 0 && longitude != 0)
            ? "https://maps.google.com?q=" + latitude + "," + longitude
            : "Location unavailable";

        String msg = "🚨 ACCIDENT DETECTED! I need help immediately!\n" +
                     "Location: " + locUrl + "\n" +
                     "Sent from Accident Detector App";

        try {
            SmsManager sms = SmsManager.getDefault();
            sms.sendTextMessage(number, null, msg, null, null);
            Toast.makeText(this, "✅ Emergency SMS sent!", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "SMS failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ─── Location ────────────────────────────────────────────────────
    @SuppressLint("MissingPermission")
    private void fetchLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            tvLocation.setText("Location permission not granted");
            return;
        }
        fusedLocation.getLastLocation().addOnSuccessListener(this, location -> {
            if (location != null) {
                latitude  = location.getLatitude();
                longitude = location.getLongitude();
                String display = String.format(Locale.getDefault(),
                    "%.5f, %.5f", latitude, longitude);
                tvLocation.setText(display);
            } else {
                tvLocation.setText("GPS signal weak, retrying...");
            }
        });
    }

    // ─── Contact Picker ──────────────────────────────────────────────
    private void openContactPicker() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.READ_CONTACTS}, REQ_PERMISSIONS);
            return;
        }
        Intent intent = new Intent(Intent.ACTION_PICK,
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI);
        startActivityForResult(intent, REQ_CONTACT_PICK);
    }

    @SuppressLint("Range")
    private void handleContactResult(Uri contactUri) {
        ContentResolver cr = getContentResolver();
        Cursor cursor = cr.query(contactUri,
            new String[]{
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            }, null, null, null);

        if (cursor != null && cursor.moveToFirst()) {
            String name   = cursor.getString(cursor.getColumnIndex(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME));
            String number = cursor.getString(cursor.getColumnIndex(
                ContactsContract.CommonDataKinds.Phone.NUMBER));
            cursor.close();

            // Clean number
            number = number.replaceAll("[^+0-9]", "");

            // Save
            prefs.edit()
                .putString(PREF_CONTACT_NAME, name)
                .putString(PREF_CONTACT_NUMBER, number)
                .apply();

            updateContactUI(name, number);
            Toast.makeText(this, "✅ Contact saved: " + name, Toast.LENGTH_SHORT).show();
        }
    }

    private void loadSavedContact() {
        String name   = prefs.getString(PREF_CONTACT_NAME, "");
        String number = prefs.getString(PREF_CONTACT_NUMBER, "");
        if (!name.isEmpty()) updateContactUI(name, number);
    }

    private void updateContactUI(String name, String number) {
        tvContactName.setText(name);
        tvContactNumber.setText(number);
    }

    private boolean hasContactSelected() {
        return !prefs.getString(PREF_CONTACT_NUMBER, "").isEmpty();
    }

    private String getSavedNumber() {
        return prefs.getString(PREF_CONTACT_NUMBER, "");
    }

    private boolean ensureContactSelected() {
        if (!hasContactSelected()) {
            new AlertDialog.Builder(this)
                .setTitle("No Emergency Contact")
                .setMessage("Please select an emergency contact first.")
                .setPositiveButton("Select Contact", (d, w) -> openContactPicker())
                .setNegativeButton("Cancel", null)
                .show();
            return false;
        }
        return true;
    }

    // ─── Permissions ─────────────────────────────────────────────────
    private void requestAllPermissions() {
        ActivityCompat.requestPermissions(this, ALL_PERMISSIONS, REQ_PERMISSIONS);
    }

    // ─── Vibration ───────────────────────────────────────────────────
    private void vibrateAlert() {
        Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (v == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            long[] pattern = {0, 400, 200, 400, 200, 800};
            v.vibrate(VibrationEffect.createWaveform(pattern, -1));
        } else {
            v.vibrate(new long[]{0, 400, 200, 400, 200, 800}, -1);
        }
    }
}
