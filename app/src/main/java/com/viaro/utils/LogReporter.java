package com.viaro.utils;

import android.content.Context;
import android.os.Environment;
import android.util.Log;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class LogReporter {
    private static final String TAG = "LogReporter";
    private static final String FOLDER_NAME = "map_viarod_log";
    private static String sLogFileName = "";

    /**
     * Initializes the log file with a unique name based on the current timestamp.
     */
    public static synchronized void init(Context context) {
        if (sLogFileName.isEmpty()) {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
            sLogFileName = "viaro_meetup_" + sdf.format(new Date()) + ".txt";
            log(context, "==================== METUP SESSION LOG STARTED ====================");
        }
    }

    /**
     * Logs a deep diagnostic entry to the log files across multiple storage paths.
     */
    public static synchronized void log(Context context, String message) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
        String timeStamp = sdf.format(new Date());
        String logLine = "[" + timeStamp + "] " + message + "\n";

        // Also output to system logcat for standard debugging
        Log.d(TAG, message);

        // Retrieve potential public/private paths to ensure maximum reliability across Android versions
        List<String> targetDirectories = getLogDirectories(context);

        for (String dirPath : targetDirectories) {
            try {
                File dir = new File(dirPath);
                if (!dir.exists()) {
                    boolean created = dir.mkdirs();
                    if (!created && !dir.exists()) {
                        continue;
                    }
                }

                File logFile = new File(dir, sLogFileName.isEmpty() ? "viaro_meetup_default.txt" : sLogFileName);
                FileWriter writer = new FileWriter(logFile, true);
                writer.write(logLine);
                writer.flush();
                writer.close();
            } catch (IOException e) {
                Log.e(TAG, "Failed to write log to: " + dirPath, e);
            }
        }
    }

    /**
     * Helper to retrieve all eligible directories to write logs into.
     */
    private static List<String> getLogDirectories(Context context) {
        List<String> paths = new ArrayList<>();

        // 1. Direct Public SD Card Root (For older Android versions / Legacy storage support)
        paths.add(Environment.getExternalStorageDirectory().getAbsolutePath() + "/" + FOLDER_NAME);
        paths.add("/sdcard/" + FOLDER_NAME);

        // 2. Public Documents directory (Standard public folder on modern Android)
        File publicDocs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
        if (publicDocs != null) {
            paths.add(publicDocs.getAbsolutePath() + "/" + FOLDER_NAME);
        }

        // 3. Public Downloads directory (Easy for users to find)
        File publicDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (publicDownloads != null) {
            paths.add(publicDownloads.getAbsolutePath() + "/" + FOLDER_NAME);
        }

        // 4. App Private External Directory (Failsafe fallback that never fails due to permissions)
        if (context != null) {
            File privateExternal = context.getExternalFilesDir(null);
            if (privateExternal != null) {
                paths.add(privateExternal.getAbsolutePath() + "/" + FOLDER_NAME);
            }
        }

        return paths;
    }
}
