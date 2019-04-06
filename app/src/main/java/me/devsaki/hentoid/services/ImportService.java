package me.devsaki.hentoid.services;

import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.support.annotation.CheckResult;
import android.support.annotation.Nullable;
import android.util.Log;

import org.greenrobot.eventbus.EventBus;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import me.devsaki.hentoid.database.ObjectBoxDB;
import me.devsaki.hentoid.database.domains.Content;
import me.devsaki.hentoid.enums.Site;
import me.devsaki.hentoid.enums.StatusContent;
import me.devsaki.hentoid.events.ImportEvent;
import me.devsaki.hentoid.notification.import_.ImportCompleteNotification;
import me.devsaki.hentoid.notification.import_.ImportStartNotification;
import me.devsaki.hentoid.util.Consts;
import me.devsaki.hentoid.util.FileHelper;
import me.devsaki.hentoid.util.JSONParseException;
import me.devsaki.hentoid.util.JsonHelper;
import me.devsaki.hentoid.util.LogUtil;
import me.devsaki.hentoid.util.Preferences;
import me.devsaki.hentoid.util.notification.ServiceNotificationManager;
import timber.log.Timber;

/**
 * Service responsible for importing an existing library.
 *
 * @see UpdateCheckService
 */
public class ImportService extends IntentService {

    private static final int NOTIFICATION_ID = 1;

    private static boolean running;
    private ServiceNotificationManager notificationManager;


    public ImportService() {
        super(ImportService.class.getName());
    }

    public static Intent makeIntent(Context context) {
        return new Intent(context, ImportService.class);
    }

    public static boolean isRunning() {
        return running;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        running = true;
        notificationManager = new ServiceNotificationManager(this, NOTIFICATION_ID);
        notificationManager.cancel();

        Timber.w("Service created");
    }

    @Override
    public void onDestroy() {
        running = false;
        Timber.w("Service destroyed");

        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    protected void onHandleIntent(@Nullable Intent intent) {
        // True if the user has asked for a cleanup when calling import from Preferences
        boolean doCleanup = false;

        if (intent != null) {
            doCleanup = intent.getBooleanExtra("cleanup", false);
        }
        startImport(doCleanup);
    }

    private void eventProgress(Content content, int nbBooks, int booksOK, int booksKO) {
        EventBus.getDefault().post(new ImportEvent(ImportEvent.EV_PROGRESS, content, booksOK, booksKO, nbBooks));
    }

    private void eventComplete(int nbBooks, int booksOK, int booksKO, File cleanupLogFile) {
        EventBus.getDefault().postSticky(new ImportEvent(ImportEvent.EV_COMPLETE, booksOK, booksKO, nbBooks, cleanupLogFile));
    }

    private void trace(int priority, List<String> memoryLog, String s, String... t) {
        s = String.format(s, (Object[]) t);
        Timber.log(priority, s);
        if (null != memoryLog) memoryLog.add(s);
    }


    /**
     * Import books from known source folders
     *
     * @param cleanup True if the user has asked for a cleanup when calling import from Preferences
     */
    private void startImport(boolean cleanup) {
        int booksOK = 0;                        // Number of books imported
        int booksKO = 0;                        // Number of folders found with no valid book inside
        int nbFolders = 0;                      // Number of folders found with no content but subfolders
        Content content = null;
        List<String> log = new ArrayList<>();

        notificationManager.startForeground(new ImportStartNotification());
        File rootFolder = new File(Preferences.getRootFolderName());

        // 1st pass : count subfolders of every site folder
        List<File> files = new ArrayList<>();
        File[] siteFolders = rootFolder.listFiles(File::isDirectory);
        if (siteFolders != null) {
            for (File f : siteFolders) files.addAll(Arrays.asList(f.listFiles(File::isDirectory)));
        }

        // 2nd pass : scan every folder for a JSON file or subdirectories
        trace(Log.DEBUG, log, "Import books starting - initial detected count : %s", files.size() + "");
        trace(Log.INFO, log, "Cleanup %s", (cleanup ? "ENABLED" : "DISABLED"));
        for (int i = 0; i < files.size(); i++) {
            File folder = files.get(i);

            try {
                content = importJson(folder);
                if (content != null) {
                    if (cleanup) {
                        String canonicalBookDir = FileHelper.formatDirPath(content);

                        String[] currentPathParts = folder.getAbsolutePath().split("/");
                        String currentBookDir = "/" + currentPathParts[currentPathParts.length - 2] + "/" + currentPathParts[currentPathParts.length - 1];

                        if (!canonicalBookDir.equals(currentBookDir)) {
                            String settingDir = Preferences.getRootFolderName();
                            if (settingDir.isEmpty()) {
                                settingDir = FileHelper.getDefaultDir(this, canonicalBookDir).getAbsolutePath();
                            }

                            if (FileHelper.renameDirectory(folder, new File(settingDir, canonicalBookDir))) {
                                content.setStorageFolder(canonicalBookDir);
                                trace(Log.INFO, log, "[Rename OK] Folder %s renamed to %s", currentBookDir, canonicalBookDir);
                            } else {
                                trace(Log.WARN, log, "[Rename KO] Could not rename file %s to %s", currentBookDir, canonicalBookDir);
                            }
                        }
                    }
                    ObjectBoxDB.getInstance(this).insertContent(content);
                    trace(Log.INFO, log, "Import book OK : %s", folder.getAbsolutePath());
                } else { // JSON not found
                    File[] subdirs = folder.listFiles(File::isDirectory);
                    if (subdirs != null && subdirs.length > 0) // Folder doesn't contain books but contains subdirectories
                    {
                        files.addAll(Arrays.asList(subdirs));
                        trace(Log.INFO, log, "Subfolders found in : %s", folder.getAbsolutePath());
                        nbFolders++;
                        continue;
                    } else { // No JSON nor any subdirectory
                        trace(Log.WARN, log, "Import book KO! (no JSON found) : %s", folder.getAbsolutePath());
                        // Deletes the folder if cleanup is active
                        if (cleanup) {
                            boolean success = FileHelper.removeFile(folder);
                            trace(Log.INFO, log, "[Remove %s] Folder %s", success ? "OK" : "KO", folder.getAbsolutePath());
                        }
                    }
                }

                if (null == content) booksKO++;
                else booksOK++;
            } catch (Exception e) {
                if (null == content)
                    content = new Content().setTitle("none").setSite(Site.NONE).setUrl("");
                booksKO++;
                trace(Log.ERROR, log, "Import book ERROR : %s %s", e.getMessage(), folder.getAbsolutePath());
            }

            eventProgress(content, files.size() - nbFolders, booksOK, booksKO);
        }
        trace(Log.INFO, log, "Import books complete - %s OK; %s KO; %s final count", booksOK + "", booksKO + "", files.size() - nbFolders + "");

        // Write cleanup log in root folder
        File cleanupLogFile = LogUtil.writeLog(this, log, buildLogInfo(cleanup));

        eventComplete(files.size(), booksOK, booksKO, cleanupLogFile);
        notificationManager.notify(new ImportCompleteNotification(booksOK, booksKO));

        stopForeground(true);
        stopSelf();
    }

    private LogUtil.LogInfo buildLogInfo(boolean cleanup) {
        LogUtil.LogInfo logInfo = new LogUtil.LogInfo();
        logInfo.logName = cleanup ? "Cleanup" : "Import";
        logInfo.fileName = cleanup ? "cleanup_log" : "import_log";
        logInfo.noDataMessage = "No content detected.";
        return logInfo;
    }


    @Nullable
    private static Content importJson(File folder) throws JSONParseException {
        File json = new File(folder, Consts.JSON_FILE_NAME_V2); // (v2) JSON file format
        if (json.exists()) return importJsonV2(json);

        Timber.w("Book folder %s : no JSON file found !", folder.getAbsolutePath());

        return null;
    }

    @CheckResult
    private static Content importJsonV2(File json) throws JSONParseException {
        try {
            Content content = JsonHelper.jsonToObject(json, Content.class);
            content.postJSONImport();

            String fileRoot = Preferences.getRootFolderName();
            content.setStorageFolder(json.getAbsoluteFile().getParent().substring(fileRoot.length()));

            if (content.getStatus() != StatusContent.DOWNLOADED
                    && content.getStatus() != StatusContent.ERROR) {
                content.setStatus(StatusContent.MIGRATED);
            }

            return content;
        } catch (Exception e) {
            Timber.e(e, "Error reading JSON (v2) file");
            throw new JSONParseException("Error reading JSON (v2) file : " + e.getMessage());
        }
    }
}
