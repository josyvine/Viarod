package com.viaro;

import android.app.Application;
import org.osmdroid.config.Configuration;
import java.io.File;

public class viaroApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        
        // Configure OSMDroid tile cache and user-agent
        Configuration.getInstance().setUserAgentValue(getPackageName());
        
        File osmCache = new File(getCacheDir(), "osmdroid");
        if (!osmCache.exists()) {
            osmCache.mkdirs();
        }
        Configuration.getInstance().setOsmdroidTileCache(osmCache);
    }
}
