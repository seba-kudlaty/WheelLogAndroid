package com.cooper.wheellog;

import timber.log.Timber;
import android.content.Context;
import java.io.File;
import android.os.Environment;
import android.util.Log;

import java.io.FileOutputStream;
import java.io.FileWriter;
import java.util.Date;
import java.util.Locale;
import java.text.SimpleDateFormat;
import com.cooper.wheellog.utils.Constants;

public class FileLoggingTree extends Timber.DebugTree {

    Context context;
    private String fileName;

    FileLoggingTree(Context context) {
        this.context = context;
        fileName = new SimpleDateFormat("yyyy-MM-dd'_'hhmm", Locale.US).format(new Date()) + ".html";
    }

    @Override
    protected void log(int priority, String tag, String message, Throwable t) {
        try {
            String ts = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(new Date());
            File file = generateFile();
            if (file != null) {
                FileWriter writer = new FileWriter(file, true);
                String line = "<p style=\"background:lightgray;\"><strong style=\"background:lightblue;\">&nbsp&nbsp" + ts + " :&nbsp&nbsp</strong>&nbsp&nbsp" + message + "</p>";
                writer.append(line);
                writer.flush();
                writer.close();
            }
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }

    private File generateFile() {
        File file = null;
        if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
            File dir = new File(Environment.getExternalStorageDirectory(), Constants.SUPPORT_FOLDER_NAME);
            boolean dirExists = true;
            if (!dir.exists())
                dirExists = dir.mkdirs();
            if (dirExists) {
                file = new File(dir, fileName);
            }
        }
        return file;
    }

}