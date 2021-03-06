package io.gigasource.hotfix_plugin;

import android.content.Context;
import android.content.SharedPreferences;

import com.tencent.tinker.lib.library.TinkerLoadLibrary;
import com.tencent.tinker.lib.tinker.Tinker;
import com.tencent.tinker.lib.tinker.TinkerInstaller;
import com.tencent.tinker.lib.tinker.TinkerLoadResult;
import com.tencent.tinker.lib.util.TinkerLog;
import com.tencent.tinker.loader.shareutil.SharePatchFileUtil;
import com.tencent.tinker.loader.shareutil.ShareTinkerInternals;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URL;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class PatchingUtil {
    private static final int MAX_DOWNLOAD_RETRY = 12;
    public static final int MAX_UPDATE_RETRY = 12;
    public static int updateCounter = 0;

    public static void loadLibrary(Context context) {
        TinkerLoadLibrary.installNavitveLibraryABI(context, "armeabi");
        System.loadLibrary("stlport_shared");
    }

    public static void cleanPatch(Context context) {
        Tinker.with(context).cleanPatch();
    }

    public static void killProcess(Context context) {
        ShareTinkerInternals.killAllOtherProcess(context);
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    public static void checkForUpdate(Context context, String domain) {
        TinkerLog.d("PatchingUtil", "Check for update");
        initUrl(context, domain);

        Tinker tinker = Tinker.with(context);
        TinkerLoadResult tinkerLoadResult = tinker.getTinkerLoadResultIfPresent();

        String patchMd5 = getMD5Code(context.getSharedPreferences(Constants.TINKER, Context.MODE_PRIVATE).getString(Constants.MD5_URL_KEY, ""));
        String currentMd5 = tinkerLoadResult.currentVersion;

        if (patchMd5.equals(currentMd5)) {
            TinkerLog.d("PatchingUtil", "Tinker patch: app is up to date");
        } else if (!patchMd5.isEmpty()) {
            TinkerLog.d("PatchingUtil", "Tinker patch: there is a newer version. Start updating");
            PatchingUtil.updateCounter = 1;
            PatchingUtil.downloadAndUpdate(context);
        }
    }

    private static void initUrl(Context context, String domain) {
        SharedPreferences sharedPreferences = context.getSharedPreferences(Constants.TINKER, Context.MODE_PRIVATE);
        sharedPreferences.edit().putString(Constants.DOMAIN_KEY, domain).apply();
        Tinker tinker = Tinker.with(context);
        if (!tinker.isTinkerLoaded()) {
            sharedPreferences.edit().putString("originalVersion", BuildConfig.VERSION_NAME).apply();
        }

        String version = sharedPreferences.getString("originalVersion", BuildConfig.VERSION_NAME);

        String patchUrl = String.format("%s/static-apk/%s/%s/%s", domain, getBuildConfigValue(context, "TOPIC"), version, Constants.APK_NAME);
        String patchPath = context.getFilesDir().getAbsolutePath() +"/" + Constants.APK_NAME;
        String md5Url = String.format("%s/md5/%s/%s/%s", domain, getBuildConfigValue(context, "TOPIC"), version, Constants.APK_NAME);

        setUrlPreferences(context, patchUrl, patchPath, md5Url);
    }

    private static void setUrlPreferences(Context context, String patchUrl, String patchPath, String md5Url) {
        SharedPreferences sharedPreferences = context.getSharedPreferences(Constants.TINKER, Context.MODE_PRIVATE);
        SharedPreferences.Editor preferencesEditor = sharedPreferences.edit();
        preferencesEditor.putString(Constants.PATCH_URL_KEY, patchUrl);
        preferencesEditor.putString(Constants.PATCH_PATH_KEY, patchPath);
        preferencesEditor.putString(Constants.MD5_URL_KEY, md5Url);
        preferencesEditor.apply();
    }

    private static String getMD5Code(String url) {
        OkHttpClient client = new OkHttpClient();
        Request request = new Request.Builder()
                .url(url)
                .build();
        try (Response response = client.newCall(request).execute()) {
            return response.code() == 200 ? response.body().string() : "";
        } catch (IOException e) {
            e.printStackTrace();
            return "";
        }
    }

    public static void downloadAndUpdate(Context context) {
        SharedPreferences sharedPreferences = context.getSharedPreferences(Constants.TINKER, Context.MODE_PRIVATE);
        String patchUrl = sharedPreferences.getString(Constants.PATCH_URL_KEY, "");
        String patchPath = sharedPreferences.getString(Constants.PATCH_PATH_KEY, "");
        String md5Url = sharedPreferences.getString(Constants.MD5_URL_KEY, "");

        downloadAndUpdate(context, patchUrl, patchPath, md5Url, 1);
    }

    private static void downloadAndUpdate(final Context context, final String patchUrl, final String patchPath, final String md5Url, final int retryDownloadCounter) {
        if (retryDownloadCounter > MAX_DOWNLOAD_RETRY) {
            TinkerLog.e("PatchingUtil", "Tinker patch: Reached maximum retry, exiting...");
            return;
        }
        downloadApk(new DownloadTask() {
            @Override
            public void onFinish(boolean success) {
                if (success) {
                    String md5 = SharePatchFileUtil.getMD5(new File(patchPath));
                    String verifyMd5 = getMD5Code(md5Url);
                    TinkerLog.d("Downloaded md5", md5);
                    TinkerLog.d("Verify md5", verifyMd5);
                    if (md5.equals(verifyMd5)) {
                        TinkerInstaller.onReceiveUpgradePatch(context, patchPath);
                    } else {
                        TinkerLog.e("PatchingUtil", "Tinker patch: incorrect MD5, retry downloading");
                        downloadAndUpdate(context, patchUrl, patchPath, md5Url, retryDownloadCounter+1);
                    }
                } else {
                    TinkerLog.e("PatchingUtil", "Download APK failed");
                    downloadAndUpdate(context, patchUrl, patchPath, md5Url, retryDownloadCounter+1);
                }
            }
        }, patchUrl, patchPath);
    }

    private static void downloadApk(DownloadTask downloadTask, String url, String savePath) {
        BufferedInputStream inStream = null;
        FileOutputStream outStream = null;
        try {
            inStream = new BufferedInputStream(new URL(url).openStream());
            outStream = new FileOutputStream(savePath);
            byte [] dataBuffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = inStream.read(dataBuffer, 0, 1024)) != -1) {
                outStream.write(dataBuffer, 0, bytesRead);
            }
            inStream.close();
            outStream.close();
            downloadTask.onFinish(true);
        } catch (IOException e) {
            e.printStackTrace();
            downloadTask.onFinish(false);
        } finally {
            try {
                if (inStream != null) inStream.close();
                if (outStream != null) outStream.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private interface DownloadTask {
        void onFinish(boolean success);
    }

    public static void checkForUpdate(final Context context) {
        Thread updateThread = new Thread(new Runnable() {
            @Override
            public void run() {
                String domain = context.getSharedPreferences(Constants.TINKER, Context.MODE_PRIVATE).getString(Constants.DOMAIN_KEY, Constants.DEFAULT_DOMAIN);
                checkForUpdate(context, domain);
            }
        });
        updateThread.setDaemon(true);
        updateThread.setName("Tinker Update");
        updateThread.start();
    }

    public static Object getBuildConfigValue(Context context, String fieldName) {
        try {
            Class<?> clazz = Class.forName(context.getPackageName() + ".BuildConfig");
            Field field = clazz.getField(fieldName);
            return field.get(null);
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        } catch (NoSuchFieldException e) {
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        }
        return null;
    }
}
