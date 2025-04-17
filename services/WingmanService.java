package co.bito.intellij.wingman.services;

import co.bito.intellij.services.FileService;
import co.bito.intellij.services.HttpService;
import co.bito.intellij.setting.Constant;
import co.bito.intellij.utils.FileStorageHandler;
import co.bito.intellij.utils.GenericUtils;
import co.bito.intellij.wingman.*;

import co.bito.intellij.wingman.modal.WingmanContext;
import co.bito.intellij.wingman.modal.WingmanLatestManifest;
import co.bito.intellij.wingman.util.WingmanUtil;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.application.ApplicationInfo;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.WindowManager;
import org.cef.callback.CefQueryCallback;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;
import java.util.function.Consumer;

import static co.bito.intellij.services.StaticFileService.readFile;
import static co.bito.intellij.utils.FileStorageHandler.BITO_WINGMAN_BIN_DIR;
import static co.bito.intellij.wingman.Constant.*;
import static co.bito.intellij.wingman.FileWriteOperations.*;
import static co.bito.intellij.wingman.WingmanConstants.*;
import co.bito.intellij.wingman.modal.WingmanUserInfo;
import static co.bito.intellij.wingman.WingmanManager.*;
import static co.bito.intellij.wingman.WingmanMixPanelEvent.*;
import static co.bito.intellij.wingman.services.BinaryDownloader.*;
import static co.bito.intellij.wingman.services.WingmanPostMessageService.notifyBitoUIForWingmanStatus;
import static com.intellij.openapi.util.io.FileUtil.exists;
import co.bito.intellij.wingman.modal.BitoWingmanServer;
import co.bito.intellij.wingman.modal.DownloadedBinaryStatus;

public class WingmanService {

    private static final Logger logger = Logger.getInstance(WingmanService.class);

    /**
     * Removes a file from the filesystem
     *
     * @param filePath The path of the file to remove
     * @return true if successful, false otherwise
     */
    public static boolean removeFile(String filePath) {
        try {
            Path path = Paths.get(filePath);
            Files.deleteIfExists(path);

            // Verify deletion
            boolean deleted = !Files.exists(path);

            if (deleted) {
                logger.info("Successfully removed file: " + filePath);
            } else {
                logger.info("Failed to remove file: " + filePath);
            }

            return deleted;
        } catch (Exception e) {
            logger.info("Error removing file " + e.getMessage());
            return false;
        }
    }

    /**
     * Function to create directory (folder) in 'temp' location in user's machine.
     *
     * @param directoryPath string path
     *                      <p>
     *                      for ex:
     *                      C:\Users\<user-name>\AppData\Local\Temp\20371_82_4240_331_117_4166
     * @return Path Upon success, returns the path to the created directory.
     *         Returns null if an error occurs.
     */
    public static Path makeDirectory(String directoryPath) {
        try {
            Path path = Paths.get(directoryPath);
            // Create directory and all parent directories if they don't exist
            return Files.createDirectories(path);
        } catch (Exception error) {
            logger.info("error occurred to make directory: " + error.getMessage());
            return null;
        }
    }

    /**
     * Common function for separation of concern related to
     * BITO wingman download process.
     */
    public static void handleFreshDownload(Project project, CefQueryCallback callback) {
        try {
            triggerWingmanDownloadProcess(project, callback, success -> {
                if (success) {
                    logger.info("Fresh download process completed successfully");
                } else {
                    logger.info("Fresh download process failed");
                }
            });
            logger.info("Fresh download process initiated");
        } catch (Exception e) {
            logger.info("Error handling fresh download: " + e.getMessage());
        }
    }

    // New method that handles the fresh download with a callback
    public static void handleFreshDownloadWithCallback(Project project, CefQueryCallback callbackForUI,
            Consumer<Boolean> callback) {
        try {

            // Set download binary in progress flag to true
            WingmanConstants.isDownloadBinaryInProgress = true;

            logger.info("Wingman download process triggered");

            // Call downloadWingmanBinary with callback
            downloadWingmanBinary(project, callbackForUI, success -> {
                if (success) {
                    logger.info("Wingman binary download completed successfully");
                    callback.accept(true);
                } else {
                    logger.info("Wingman binary download failed");
                    callback.accept(false);
                }
            });

            logger.info("Wingman download process completed ");
        } catch (Exception e) {
            // Make sure to set the flag to false even if there's an error
            WingmanConstants.isDownloadBinaryInProgress = false;
            logger.info(
                    "Error in handleFreshDownloadWithCallback: " + e.getMessage());
            callback.accept(false);
        }
    }

    /**
     * Triggers the Wingman download process
     */
    public static void triggerWingmanDownloadProcess(Project project, CefQueryCallback callbackForUI,
            Consumer<Boolean> callback) {
        try {
            // Set download binary in progress flag to true
            WingmanConstants.isDownloadBinaryInProgress = true;

            downloadWingmanBinary(project, callbackForUI, success -> {
                if (success) {
                    logger.info("Wingman binary is download completed successfully");
                    // Set download binary in progress flag to false ONLY after download completes
                    WingmanConstants.isDownloadBinaryInProgress = false;
                    // Call the callback with success
                    callback.accept(true);
                } else {
                    logger.info("Wingman binary download failed");
                    // Set download binary in progress flag to false on failure
                    WingmanConstants.isDownloadBinaryInProgress = false;
                    // Call the callback with failure
                    callback.accept(false);
                }
            });

            logger.info("Wingman download process triggered");
        } catch (Exception e) {
            // Make sure to set the flag to false even if there's an error
            WingmanConstants.isDownloadBinaryInProgress = false;
            logger.info("error caught to trigger wingman download process: " + e.getMessage(),
                    e);
            callback.accept(false);
        }
    }

    public static void downloadWingmanBinary(Project project, CefQueryCallback callbackForUI,
            Consumer<Boolean> finalCallback) {
        try {
            WingmanUserInfo wingmanUser = WingmanContext.getUserInfo(project);

            JsonObject messageToSend = new JsonObject();
            messageToSend.addProperty("key", WingmanConstants.WINGMAN_DOWNLOAD_STARTED);

            notifyBitoUIForWingmanStatus(wingmanUser, messageToSend, project);

            callToMixPanelAboutWingman(
                    wingmanUser,
                    WINGMAN_DOWNLOAD_START,
                    "SUCCESS",
                    "ide_window");

            WingmanConstants.BinaryToDownloadByPlatform binaryToDownloadByPlatform = getPlatformBinaryName(project);

            if (!(isBinaryToDownloadValid(binaryToDownloadByPlatform, wingmanUser))) {
                logger.info("skip wingman download for invalid version");
                if (finalCallback != null)
                    finalCallback.accept(false);
                return;
            }

            BinaryDownloader downloader = new BinaryDownloader(binaryToDownloadByPlatform);
            String wingmanBaseDir = downloader.getBaseBinaryDirectory();

            BitoWingmanServer wingmanServerInfo = new BitoWingmanServer();
            DownloadedBinaryStatus downloadStatus = new DownloadedBinaryStatus();

            downloadStatus.setAvailableBinaryVersion(downloader.getAvailableBinaryVersion());
            downloadStatus.setAvailableToolsVersion(downloader.getAvailableToolsVersion());

            // store per project
            WingmanContext.setServerInfo(project, wingmanServerInfo);
            WingmanContext.setDownloadStatus(project, downloadStatus);

            // Run in background to avoid UI blocking
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                try {
                    // Download configuration file
                    downloadStatus.setBaseBinaryDir(wingmanBaseDir);

                    // Check if tools already exist
                    boolean isToolsAlreadyExists = isWingmanToolsExists(project);

                    // Handle tools with callback approach
                    if (!isToolsAlreadyExists) {
                        logger.info("start downloading bito wingman tools");

                        // Use callback approach for tools download startDownloadingWingmanTools
                        startDownloadingWingmanTools(project, callbackForUI, downloader, toolsSuccess -> {
                            if (toolsSuccess) {
                                // Tools download succeeded, proceed with binary download
                                continueWithBinaryDownload(downloader, downloadStatus, wingmanServerInfo,
                                        wingmanUser, messageToSend, project, finalCallback);
                            } else {
                                // Tools download failed
                                ApplicationManager.getApplication().invokeLater(() -> {
                                    messageToSend.addProperty(
                                            "key", WingmanConstants.WINGMAN_DOWNLOAD_FAILED);
                                    notifyBitoUIForWingmanStatus(wingmanUser, messageToSend, project);
                                    callToMixPanelAboutWingman(
                                            wingmanUser,
                                            WINGMAN_DOWNLOAD_FAILED,
                                            "ERROR",
                                            "ide_window");
                                    if (finalCallback != null)
                                        finalCallback.accept(false);
                                });
                            }
                        });
                    } else {
                        // Tools already exist, but we need to check for updates before proceeding
                        logger.info("skip downloading bito wingman tools, already exists: "
                                + isToolsAlreadyExists);

                        // Call checkForBinaryToolsUpdate with a callback to wait for completion
                        checkForBinaryToolsUpdate(project, callbackForUI, null, true, wingmanUser,
                                toolsUpdateResult -> {
                                    // Continue with binary download only after tools update completes
                                    continueWithBinaryDownload(downloader, downloadStatus, wingmanServerInfo,
                                            wingmanUser, messageToSend, project, finalCallback);
                                });
                    }
                } catch (Exception error) {
                    logger.info("Error caught in downloading of Bito Wingman: "
                            + error.getMessage());
                    ApplicationManager.getApplication().invokeLater(() -> {
                        messageToSend.addProperty(
                                "key", WingmanConstants.WINGMAN_DOWNLOAD_FAILED);
                        notifyBitoUIForWingmanStatus(wingmanUser, messageToSend, project);

                        callToMixPanelAboutWingman(
                                wingmanUser,
                                WINGMAN_DOWNLOAD_END,
                                "ERROR",
                                "ide_window");
                        if (finalCallback != null)
                            finalCallback.accept(false);
                    });
                }
            });
        } catch (Exception e) {
            logger.info("Unexpected error in downloadWingmanBinary: " + e.getMessage());
            if (finalCallback != null)
                finalCallback.accept(false);
        }
    }

    // Helper method to continue with binary download after tools are downloaded
    private static void continueWithBinaryDownload(
            BinaryDownloader downloader,
            DownloadedBinaryStatus downloadStatus,
            BitoWingmanServer initialWingmanServerInfo,
            WingmanUserInfo wingmanUser,
            JsonObject messageToSend,
            Project project,
            Consumer<Boolean> finalCallback) {

        // Now proceed with binary download using callback
        downloader.downloadBinary(project, FROM_WHERE_TO_DOWNLOAD_BINARY, binaryStatus -> {
            try {
                if (!binaryStatus.getIsBinaryDownloaded()) {
                    logger.info("Error observed to download or update bito wingman "
                            + binaryStatus.getErrorMsg());

                    ApplicationManager.getApplication().invokeLater(() -> {
                        messageToSend.addProperty(
                                "key", WingmanConstants.WINGMAN_DOWNLOAD_FAILED);

                        notifyBitoUIForWingmanStatus(wingmanUser, messageToSend, project);

                        callToMixPanelAboutWingman(
                                wingmanUser,
                                WINGMAN_DOWNLOAD_END,
                                "ERROR",
                                "ide_window");

                        String errMsg = binaryStatus.getErrorMsg().toLowerCase();
                        if (errMsg.equals("download cancelled")) {
                            logger.info("Bito Wingman download cancelled by user");
                        }
                        if (finalCallback != null)
                            finalCallback.accept(false);
                    });
                    return;
                }

                String wingmanBaseDir = downloader.getBaseBinaryDirectory();

                initialWingmanServerInfo.setBinaryPath(wingmanBaseDir);
                initialWingmanServerInfo.setHost("localhost");
                initialWingmanServerInfo.setPort(0);

                // Get and set version information
                String wingmanVersion = downloadStatus.getAvailableBinaryVersion();
                initialWingmanServerInfo.setInstalledVersion(wingmanVersion);

                // Create a final reference to the server info for use in the lambda
                final BitoWingmanServer finalWingmanServerInfo = initialWingmanServerInfo;

                writeToMaintainDownloadStatus(project, binaryStatus, finalWingmanServerInfo, true);

                // Notify UI about Wingman status
                ApplicationManager.getApplication().invokeLater(() -> {
                    messageToSend.addProperty(
                            "key", WingmanConstants.WINGMAN_DOWNLOAD_FINISHED);

                    notifyBitoUIForWingmanStatus(wingmanUser, messageToSend, project);

                    // Send analytics event
                    callToMixPanelAboutWingman(
                            wingmanUser,
                            WINGMAN_DOWNLOAD_END,
                            "SUCCESS",
                            "ide_window");
                    if (finalCallback != null)
                        finalCallback.accept(true);
                });
            } catch (Exception error) {
                logger.info("Error caught in binary download: " + error.getMessage());
                ApplicationManager.getApplication().invokeLater(() -> {
                    messageToSend.addProperty(
                            "key", WingmanConstants.WINGMAN_DOWNLOAD_FAILED);

                    notifyBitoUIForWingmanStatus(wingmanUser, messageToSend, project);

                    callToMixPanelAboutWingman(
                            wingmanUser,
                            WINGMAN_DOWNLOAD_END,
                            "ERROR",
                            "ide_window");
                    if (finalCallback != null)
                        finalCallback.accept(false);
                });
            }
        });
    }

    public static boolean checkIfDownloadIsInProgressByJB() {
        try {
            String homeDir = System.getProperty("user.home");
            String bitoWingmanDir = Paths.get(homeDir, ".bitowingman", "bin").toString();
            String processIdFilePath = Paths.get(bitoWingmanDir, "download.pid").toString();
            File pidFile = new File(processIdFilePath);

            logger.info("Checking if Wingman binary download is already in progress...");

            if (!exists(bitoWingmanDir)) {
                makeDirectory(bitoWingmanDir);
                return false;
            }

            if (!pidFile.exists()) {
                return false;
            }

            try (RandomAccessFile raf = new RandomAccessFile(pidFile, "r");
                    FileChannel channel = raf.getChannel();
                    FileLock lock = channel.lock(0, Long.MAX_VALUE, true)) {

                String pid = raf.readLine();

                if (pid == null || pid.trim().isEmpty()) {
                    logger.info("PID file is empty or invalid. Removing stale PID file.");
                    FileService.deleteFile(pidFile);
                    return false;
                }

                long processId = Long.parseLong(pid.trim());
                boolean isRunning = ProcessHandle.of(processId).isPresent();

                logger.info("Wingman download in progress: " + isRunning);

                if (!isRunning) {
                    FileService.deleteFile(pidFile);
                }

                return isRunning;

            } catch (NumberFormatException ex) {
                logger.info("Invalid PID format. Removing stale PID file.");
                FileService.deleteFile(pidFile);
            } catch (Exception ex) {
                logger.info("Error while locking/reading PID file: " + ex.getMessage());
                FileService.deleteFile(pidFile);
            }

        } catch (Exception e) {
            logger.info("Error while checking download progress: " + e.getMessage());
        }

        return false;
    }

    /**
     * Reads the binary process PID from the PID info file.
     *
     * @return The process ID, or 0 if not found or invalid
     */
    public static Long readBinaryProcessPidInfo() {
        try {
            // Construct path to process.pid file
            Path bitoWingmanDir = Path.of(System.getProperty("user.home"), ".bitowingman", "bin");
            Path processIdFilePath = bitoWingmanDir.resolve("process_jb.pid");

            // Check if file exists
            if (Files.exists(processIdFilePath)) {
                // Read and trim the content
                String pid = Files.readString(processIdFilePath).trim();

                try {
                    // Convert to number and return
                    return Long.parseLong(pid);

                } catch (NumberFormatException e) {
                    logger.info("Invalid PID format in file: " + pid);
                    return 0L;
                }
            }
        } catch (Exception e) {
            logger.info("Error reading PID file: " + e.getMessage());
        }

        return 0L;
    }

    /**
     * Removes the PID file after download.
     */
    public static void removePidFileAfterDownload() {
        try {
            Path bitoWingmanDir = Path.of(System.getProperty("user.home"), ".bitowingman", "bin");
            Path processIdFilePath = bitoWingmanDir.resolve("download.pid");

            // Check if file exists
            if (Files.exists(processIdFilePath)) {
                // Get current process ID
                String currentPid = String.valueOf(ProcessHandle.current().pid());

                // Read existing PID from file with proper error handling
                String existingPid;
                try {
                    existingPid = Files.readString(processIdFilePath, StandardCharsets.UTF_8).trim();
                } catch (IOException e) {
                    logger.info("Error reading PID file : " + e.getMessage());
                    return;
                }

                if (existingPid.equals(currentPid)) {
                    // Delete the file if PIDs match
                    try {
                        Files.delete(processIdFilePath);
                        logger.info("Download completed, PID file removed.");
                    } catch (IOException e) {
                        logger.info("Error deleting PID file: " + e.getMessage());
                    }
                } else {
                    logger.info("PID mismatches in the file, will not be removed.");
                }
            }
        } catch (Exception e) {
            logger.info("Error in removePidFileAfterDownload: " + e.getMessage());
        }
    }

    /**
     * Function to check the Wingman configuration is up-to-date
     * before Wingman binary monitoring starts.
     * - Acquires a lock to prevent multiple processes from modifying
     * the config file at the same time.
     * - Compare the current config with the backup config to check for changes.
     * - If changes are detected, updates `toolsDir` and `server.api.port` from the
     * backup.
     * - Release the lock after the function executes.
     *
     * @return true if configuration was updated, false otherwise
     */
    public boolean checkAndUpdateConfigBeforeWingmanMonitor(Project project) {
        final String LOCK_FILE = "config.lock";

        BinaryDownloader downloader = new BinaryDownloader();
        String baseBinaryDir = downloader.getBaseBinaryDirectory();

        String dirPathToSave = getDirPathForJBConfigFile(project);

        Path configFilePath = Path.of(baseBinaryDir + dirPathToSave, "config.json");
        Path backupConfigFilePath = Path.of(baseBinaryDir + dirPathToSave, "config_bk.json");
        Path envConfigFilePath = Path.of(baseBinaryDir + dirPathToSave, "env");

        Path lockFilePath = Path.of(baseBinaryDir, LOCK_FILE);
        String workingDir = project.getBasePath();

        try {
            // Acquire lock
            acquireLockOnPath(workingDir, lockFilePath.toString());

            // Compare configs
            boolean hasConfigChanged = compareWingmanConfig(
                    configFilePath.toString(),
                    backupConfigFilePath.toString(),
                    project);

            logger.info("Configuration mis-matched: " + hasConfigChanged);

            if (!hasConfigChanged) {
                return false;
            }

            // Read the current config.json
            String configFileContents = Files.readString(configFilePath, StandardCharsets.UTF_8);
            JsonObject configFileData = JsonParser.parseString(configFileContents).getAsJsonObject();

            // Read the backup config.json
            String backupConfigFileContents = Files.readString(backupConfigFilePath, StandardCharsets.UTF_8);
            JsonObject backupConfigFileData = JsonParser.parseString(backupConfigFileContents).getAsJsonObject();

            // Extract values from backup or current config
            int serverPort;
            String toolsDir;

            // Get server port
            if (backupConfigFileData.has("server") &&
                    backupConfigFileData.getAsJsonObject("server").has("api") &&
                    backupConfigFileData.getAsJsonObject("server").getAsJsonObject("api").has("port")) {

                serverPort = backupConfigFileData.getAsJsonObject("server")
                        .getAsJsonObject("api").get("port").getAsInt();
            } else {
                serverPort = configFileData.getAsJsonObject("server")
                        .getAsJsonObject("api").get("port").getAsInt();
            }

            // Get tools directory
            if (backupConfigFileData.has("paths") &&
                    backupConfigFileData.getAsJsonObject("paths").has("toolsDir")) {

                toolsDir = backupConfigFileData.getAsJsonObject("paths").get("toolsDir").getAsString();
            } else {
                toolsDir = configFileData.getAsJsonObject("paths").get("toolsDir").getAsString();
            }

            // Update the current config.json with the backup values
            configFileData.getAsJsonObject("server").getAsJsonObject("api").addProperty("port", serverPort);
            configFileData.getAsJsonObject("paths").addProperty("toolsDir", toolsDir);

            // Add the envFile parameter to the paths section
            configFileData.getAsJsonObject("paths").addProperty("envFilePath", envConfigFilePath.toString());

            logger.info("Restoring toolsDir: " + toolsDir + " -> "
                    + configFilePath);

            // Serialize and write back to file
            String serializedData = new GsonBuilder().setPrettyPrinting().create().toJson(configFileData);

            // Set file permissions (similar to FILE_PERMISSION_READ_AND_WRITE)
            Files.writeString(configFilePath, serializedData, StandardCharsets.UTF_8);

            // Also update the backup file with the latest changes
            Files.writeString(backupConfigFilePath, serializedData, StandardCharsets.UTF_8);
            logger.info("Updated backup config file with current configuration");

            // Set permissions if on Unix-like system
            WingmanUtil.setFilePermissionsSafely(configFilePath);
            WingmanUtil.setFilePermissionsSafely(backupConfigFilePath);

            return hasConfigChanged;

        } catch (Exception e) {
            logger.info(
                    "Error comparing wingman configuration before start of process: "
                            + e.getMessage());
            return false;
        } finally {
            try {
                // Release the lock
                releaseLockOnPath(lockFilePath.toString());
            } catch (Exception e) {
                logger.info("Error releasing lock: " + e.getMessage());
            }
        }
    }

    /**
     * Gets the Wingman working directory.
     * Returns the first workspace folder if available, otherwise the user's home
     * directory.
     */
    public static String getWingmanWorkingDirectory(Project project) {
        return (project != null && project.getBasePath() != null) ? project.getBasePath() : "";
    }

    /**
     * Function to compare original and backup Wingman config files
     * to check if `toolsDir` or `server.api.port` values have changed.
     *
     * @param originalFilePath Path to the original config file
     * @param backupFilePath   Path to the backup config file
     * @return true if configuration has changed, false otherwise
     */
    public static boolean compareWingmanConfig(String originalFilePath, String backupFilePath, Project project) {
        try {
            Path originalPath = Path.of(originalFilePath);
            Path backupPath = Path.of(backupFilePath);
            if (!Files.exists(originalPath)) {
                BitoWingmanServer serverInfo = WingmanContext.getServerInfo(project);
                if (serverInfo != null) {
                    new WingmanManager().updatePathsInWingmanConfig(project, serverInfo);
                } else {
                    //this will never happen, because till this serverInfo should be available always, added else only for logging.
                    logger.info("Wingman server info is null, cannot proceed with creating config file.");
                    return false;
                }
            }
            if (!Files.exists(backupPath)) {
                // Create a backup of the original file
                Files.copy(originalPath, backupPath, StandardCopyOption.REPLACE_EXISTING);
            }

            // Read contents of both files
            String originalFileContent = Files.readString(originalPath, StandardCharsets.UTF_8);
            String backupFileContent = Files.readString(backupPath, StandardCharsets.UTF_8);

            // Parse JSON data using Gson
            Gson gson = new Gson();
            JsonObject originalData = gson.fromJson(originalFileContent, JsonObject.class);
            JsonObject backupData = gson.fromJson(backupFileContent, JsonObject.class);

            // Extract tools dir path and port number
            String originalToolsDir = "";
            String backupToolsDir = "";

            if (originalData.has("paths") && originalData.getAsJsonObject("paths").has("toolsDir")) {
                originalToolsDir = originalData.getAsJsonObject("paths").get("toolsDir").getAsString();
            }

            if (backupData.has("paths") && backupData.getAsJsonObject("paths").has("toolsDir")) {
                backupToolsDir = backupData.getAsJsonObject("paths").get("toolsDir").getAsString();
            }

            // Extract port numbers
            String originalPort = "";
            String backupPort = "";

            if (originalData.has("server") && originalData.getAsJsonObject("server").has("api") &&
                    originalData.getAsJsonObject("server").getAsJsonObject("api").has("port")) {
                originalPort = originalData.getAsJsonObject("server")
                        .getAsJsonObject("api").get("port").toString();
            }

            if (backupData.has("server") && backupData.getAsJsonObject("server").has("api") &&
                    backupData.getAsJsonObject("server").getAsJsonObject("api").has("port")) {
                backupPort = backupData.getAsJsonObject("server")
                        .getAsJsonObject("api").get("port").toString();
            }

            // Compare values
            if (!originalToolsDir.equals(backupToolsDir) || !originalPort.equals(backupPort)) {
                return true; // Configuration mismatch / changed
            }

            return false; // Configuration matched
        } catch (Exception e) {
            logger.info("Error comparing Wingman config files: " + e.getMessage());
            return false;
        }
    }

    /**
     * Creates a file containing the process ID of the Wingman binary.
     *
     * @param processID The process ID to write to the file
     * @throws IOException If an I/O error occurs
     */
    public void createBinaryProcessPidInfo(String processID) throws IOException {
        // Create the path to the Wingman directory and PID file
        Path bitoWingmanDir = Path.of(System.getProperty("user.home"), ".bitowingman", "bin");
        Path processIdFilePath = bitoWingmanDir.resolve("process_jb.pid");

        try {
            // Ensure the directory exists
            Files.createDirectories(bitoWingmanDir);

            // Write the process ID to the file
            Files.writeString(processIdFilePath, processID, StandardCharsets.UTF_8);

            // Set permissions if on Unix-like system
            WingmanUtil.setFilePermissionsSafely(processIdFilePath);

            logger.info("Process details written to file with PID: " + processID);
        } catch (IOException e) {
            logger.info("Error creating process PID file: " + e.getMessage());
        }
    }

    /**
     * Send HTTP response from IDE backend service to BITO webview (UI)
     * <p>
     * Case: the wingman binary name is not available or not supported by BITO.
     */
    public static void sendResponseForUnsupportedWingman(CefQueryCallback callback) {
        // Create response message
        String message = "The platform or operating system is invalid or not supported.";

        // Return callback for failure
        callback.failure(HTTP_STATUS_BAD_REQUEST, message);
    }

    /**
     * Checks if a Wingman download is already in progress by another instance
     *
     * @return boolean true if download is in progress, false otherwise
     */
    public static void createPidFileBeforeDownload() {
        String bitoWingmanDir = BITO_WINGMAN_BIN_DIR;
        String pidFilePath = Paths.get(bitoWingmanDir, "download.pid").toString();
        String processId = String.valueOf(ProcessHandle.current().pid());

        try {
            // Ensure directory exists
            makeDirectory(bitoWingmanDir);

            File pidFile = new File(pidFilePath);

            // Lock and write PID atomically
            try (RandomAccessFile raf = new RandomAccessFile(pidFile, "rw");
                    FileChannel channel = raf.getChannel();
                    FileLock lock = channel.lock()) {

                channel.truncate(0); // Clear previous content
                channel.write(ByteBuffer.wrap(processId.getBytes(StandardCharsets.UTF_8)));

                logger.info("Download PID file created with PID: " + processId);

            } catch (IOException e) {
                logger.info("Failed to write download.pid file with lock: " + e.getMessage());
            }

        } catch (Exception e) {
            logger.info("Error while preparing download PID file: " + e.getMessage());
        }
    }

    /**
     * Creates a JB instance tracker file if it doesn't exist
     *
     * @param fileNameToCreate The name of the file to create
     * @return String The path to the created file
     */
    public static String createJBInstanceTrackerFile(String fileNameToCreate) {
        try {
            // Get the bito folder path
            String bitoHomeDir = FileStorageHandler.BITO_DIR;

            // Create directory if it doesn't exist
            boolean isBitoFolderExists = exists(bitoHomeDir);
            if (!isBitoFolderExists) {
                makeDirectory(bitoHomeDir);
            }

            // Create file path
            String jbInstancesTrackerFile = Paths.get(bitoHomeDir, fileNameToCreate).toString();

            File file = new File(jbInstancesTrackerFile.toString());

            // Create empty JSON structure
            JSONObject jsonData = new JSONObject();
            jsonData.put("instances", new JSONArray());
            String serializedData = jsonData.toString(2); // Pretty print with 2-space indent

            // Write to file if it doesn't exist
            if (!file.exists()) {
                write(serializedData, jbInstancesTrackerFile, FILE_PERMISSION_READ_AND_WRITE);
            }

            return jbInstancesTrackerFile;
        } catch (Exception e) {
            logger.info("Error creating JB instance tracker file: " + e.getMessage());
            return "";
        }
    }

    public static void writeToJBInstanceTrackerFile(JetBrainsIDEInstance instance, String filePath) {
        try {
            File file = new File(filePath);
            JSONObject jsonData;
            JSONArray instances;

            // Read existing content or initialize
            if (file.exists() && file.length() > 0) {
                String fileContent = readFile(filePath);
                jsonData = new JSONObject(fileContent);
                instances = jsonData.optJSONArray("instances");
                if (instances == null)
                    instances = new JSONArray();
            } else {
                jsonData = new JSONObject();
                instances = new JSONArray();
            }

            boolean updated = false;
            String currentProjectPath = instance.getProjectPath();

            for (int i = 0; i < instances.length(); i++) {
                JSONObject obj = instances.getJSONObject(i);
                if (instance.getProjectId().equals(obj.optString("id"))) {
                    // Match found: check workspaceFolders
                    JSONArray folders = obj.optJSONArray("workspaceFolders");
                    if (folders == null)
                        folders = new JSONArray();

                    boolean pathExists = false;
                    for (int j = 0; j < folders.length(); j++) {
                        if (folders.getString(j).equals(currentProjectPath)) {
                            pathExists = true;
                            break;
                        }
                    }

                    if (!pathExists) {
                        folders.put(currentProjectPath);
                        obj.put("workspaceFolders", folders);
                        logger.info(
                                "Appended new workspace path: " + currentProjectPath);
                    } else {
                        logger.info("Skipping duplicate workspace path: "
                                + currentProjectPath);
                    }

                    updated = true;
                    break;
                }
            }

            if (!updated) {
                JSONObject newInstance = new JSONObject();
                newInstance.put("id", instance.getProjectId());
                newInstance.put("projectName", instance.getProjectName());
                newInstance.put("projectPath", instance.getProjectPath());
                newInstance.put("workspaceFolders", new JSONArray(Collections.singletonList(currentProjectPath)));
                newInstance.put("hasWorkspace", instance.isHasWorkspace());
                newInstance.put("userId", instance.getUserId());
                newInstance.put("wsId", instance.getWsId());

                instances.put(newInstance);
                logger.info(
                        "Created new IDE session entry for: " + instance.getProjectId());
            }

            jsonData.put("instances", instances);
            write(jsonData.toString(2), filePath, FILE_PERMISSION_READ_AND_WRITE);

        } catch (Exception e) {
            logger.info("Error writing instance info: " + e.getMessage());
        }
    }

    public static void sendResponseForServerStartInProgress(CefQueryCallback callback) {
        try {

            logger.info("send response: Bito Wingman Server start is in progress.");

            JSONObject responseJson = new JSONObject();
            responseJson.put("status", HTTP_SUCCESS_RESPONSE);
            responseJson.put("message", "BITO Wingman Server is starting ...");

            callback.success(responseJson.toString());
        } catch (Exception e) {
            logger.info("Error creating JSON response: " + e.getMessage());
            callback.failure(500, "Error creating response");
        }
    }

    public static void sendResponseForDownloadInProgress(CefQueryCallback callback, Boolean isFirstTimeDownload) {
        try {

            logger.info("send response: Bito Wingman download is in progress.");

            JSONObject responseJson = new JSONObject();
            responseJson.put("status", HTTP_SUCCESS_RESPONSE);
            responseJson.put("isDownloadInProgress", true);
            responseJson.put("message", "BITO Wingman download is already in progress");

            callback.success(responseJson.toString());
        } catch (Exception e) {
            logger.info("Error creating JSON response: " + e.getMessage());
            callback.failure(500, "Error creating response");
        }
    }

    /**
     * Helper function to check if Bito Wingman Tools
     * update is required
     *
     * @param requestBody         Request object (may be null)
     * @param isBinaryAlsoMissing Flag indicating if binary is also missing
     * @param wingmanUser         User information
     */
    public static void checkForBinaryToolsUpdate(
            Project project,
            CefQueryCallback callbackForUI,
            Map<String, Object> requestBody,
            Boolean isBinaryAlsoMissing,
            WingmanUserInfo wingmanUser,
            Consumer<Boolean> callback) {

        try {
            WingmanVersionChecker wingmanVersionChecker = new WingmanVersionChecker();
            boolean isBinaryToolsUpdateRequired = wingmanVersionChecker.isBinaryToolsUpdateRequired(project);

            logger.info(
                    "Bito wingman tools update is required " + isBinaryToolsUpdateRequired);

            if (!isBinaryToolsUpdateRequired) {
                JSONObject response = new JSONObject();
                response.put("status", HTTP_SUCCESS_RESPONSE);
                response.put("message", "Bito Wingman tools update not required");
                callbackForUI.success(response.toString());
                callback.accept(true);
                return;
            }

            // Check if another IDE instance is already downloading
            if (checkIfDownloadIsInProgressByJB()) {
                logger.info("Another IDE instance is downloading Wingman. Aborting duplicate update.");
                callback.accept(false);
                return;
            }

            if (isBinaryAlsoMissing == null || !isBinaryAlsoMissing) {
                JsonObject messageToSend = new JsonObject();
                messageToSend.addProperty(
                        "key", WingmanConstants.WINGMAN_DOWNLOAD_STARTED);

                notifyBitoUIForWingmanStatus(wingmanUser, messageToSend, project);

                JSONObject response = new JSONObject();
                response.put("status", HTTP_SUCCESS_RESPONSE);
                response.put("message", "Bito Wingman tools update is in progress");
                callbackForUI.success(response.toString());
            }

            WingmanLatestManifest wingmanManifest = WingmanConstants.wingmanLatestManifest;
            String availableVersion = wingmanManifest.getToolsVersion();

            DownloadedBinaryStatus downloadStatus = WingmanContext.getDownloadStatus(project);
            downloadStatus.setAvailableToolsVersion(availableVersion);

            WingmanConstants.BinaryToDownloadByPlatform binaryToDownload = getPlatformBinaryName(project);

            BinaryDownloader downloader = new BinaryDownloader(binaryToDownload);
            String wingmanToolsDir = downloader.getWingmanToolsDir();

            WingmanConstants.isDownloadBinaryInProgress = true;

            // Create the PID to track this download
            // createPidFileBeforeDownload();

            downloader.downloadWingmanTools(project, true, downloadResult -> {
                try {
                    if ("true".equals(downloadResult[0])) {
                        FileService fileService = new FileService();
                        String toolsZipFilePath = downloadResult[1];
                        String zipToBeExtractedAt = downloadResult[2];

                        logger.info("Calling to remove tools extracted at: "
                                + zipToBeExtractedAt + " and wingman tools dir: " + wingmanToolsDir);

                        Path zipFilePath = Paths.get(zipToBeExtractedAt);
                        Path toolsTempDir = zipFilePath.getParent();
                        Path sourceDir = toolsTempDir.resolve("tools");
                        Path targetDir = Paths.get(wingmanToolsDir);

                        fileService.copyAndReplaceFiles(sourceDir, targetDir);
                        fileService.deleteDir(toolsTempDir.toString());

                        WingmanConstants.isDownloadBinaryInProgress = false;

                        JsonObject messageToSend = new JsonObject();
                        messageToSend.addProperty("key", WingmanConstants.WINGMAN_DOWNLOAD_FINISHED);
                        notifyBitoUIForWingmanStatus(wingmanUser, messageToSend, project);

                        logger.info("Download and extraction successful. Tools at: " + zipToBeExtractedAt);
                        callback.accept(true);
                    } else {
                        logger.info("Download or extraction failed");
                        callback.accept(false);
                    }
                } catch (Exception e) {
                    // removePidFileAfterDownload();

                    logger.info("Error checking for binary tools update: " + e.getMessage());
                    JSONObject response = new JSONObject();
                    try {
                        response.put("status", HTTP_STATUS_BAD_REQUEST);
                        response.put("message", "Error checking for Bito Wingman tools update");
                    } catch (JSONException ex) {
                        logger.info("Error adding status code and message.");
                    }
                    callbackForUI.failure(500, response.toString());

                    // Call the callback with failure status
                    callback.accept(false);

                }
            });

        } catch (Exception e) {
            logger.info("Error checking for binary tools update: " + e.getMessage());
            JSONObject response = new JSONObject();
            try {
                response.put("status", HTTP_STATUS_BAD_REQUEST);
                response.put("message", "Error checking for Bito Wingman tools update");
            } catch (JSONException ex) {
                logger.info("Error adding status code and message.");
            }
            callbackForUI.failure(500, response.toString());

            // Call the callback with failure status
            callback.accept(false);
        }
    }

    /**
     * Helper function to check if Bito Wingman Binary
     * update is required
     *
     * @param requestBody     Request object
     * @param wingmanUserInfo User information
     * @return true if an update was performed, false otherwise
     */
    public static boolean checkForBinaryUpdate(Project project, CefQueryCallback callback,
            WingmanUserInfo wingmanUserInfo, Map<String, Object> requestBody) {
        try {
            WingmanVersionChecker wingmanVersionChecker = new WingmanVersionChecker();

            boolean isBinaryUpdateRequired = wingmanVersionChecker.isBinaryUpdateRequired(project);
            logger.info("Is wingman binary update required : " + isBinaryUpdateRequired);
            if (isBinaryUpdateRequired) {
                boolean isInProgress = checkIfDownloadIsInProgressByJB();
                if (isInProgress) {
                    JsonObject messageToSend = new JsonObject();
                    messageToSend.addProperty(
                            "key", WingmanConstants.WINGMAN_DOWNLOAD_IN_PROGRESS);
                    
                    notifyBitoUIForWingmanStatus(wingmanUserInfo, messageToSend, project);

                    sendResponseForDownloadInProgress(callback, false);
                    return false; // If download is in progress, return false
                }

                createPidFileBeforeDownload();

                wingmanVersionChecker.downloadBinaryUpdate(project, callback, requestBody);
                removePidFileAfterDownload();
                return true;
            }
            return false;
        } catch (Exception e) {
            logger.info("Error checking for binary update: " + e.getMessage());
            return false;
        }
    }

    /**
     * Checks if a download is already in progress and handles the response
     *
     * @param requestBody Request object
     * @return true if no download is in progress, false otherwise
     */
    public static boolean handleDownloadInProgress(Map<String, Object> requestBody, CefQueryCallback callback) {
        try {
            // Check if download is in progress within the same IDE (using flag)
            if (WingmanConstants.isDownloadBinaryInProgress) {
                sendResponseForDownloadInProgress(callback, false);
                return false;
            }
            return true; // No download in progress, proceed
        } catch (Exception e) {
            logger.info("Error handling download in progress check: " + e.getMessage());
            return false;
        }
    }

    /**
     * Function to get the build environment for current
     * BITO as JetBrains plugin.
     *
     * @return (' PROD ' | ' PREPROD ' | ' STAGING ')
     */
    public String getExtensionBuildEnv() {
        String envName = "STAGING";
        boolean isPreprod = Constant.IS_PREPROD;
        try {
            String buildEnv = GenericUtils.getEnvironment();
            if (buildEnv.equalsIgnoreCase(Constant.PROD) && !isPreprod) {
                envName = "PROD";

            } else if (buildEnv.equalsIgnoreCase(Constant.PROD) && isPreprod) {
                envName = "PREPROD";

            } else {
                envName = "STAGING";
            }
            return envName;
        } catch (Exception e) {
            logger.info("error to get build environment for plugin: " + e.getMessage());
            return envName;
        }
    }

    public Map<String, Object> setWingmanUserFromDefaultProfile() {
        // get profile details
        try {

            JsonObject profileObject = FileStorageHandler.getProfileDetailsOnLoad(null, null);

            WingmanUtil util = new WingmanUtil();
            Map<String, Object> userInfo = util.extractDefaultUserFromProfile(profileObject);

            return userInfo;

        } catch (Exception ex) {
            logger.info("exception caught to set wingman user default profile "
                    + ex.getMessage());
        }
        return new HashMap<String, Object>();
    }

    public boolean compareAndUpdateWingmanConfig(Project project, String wingmanBaseDir) {
        WingmanUtil util = new WingmanUtil();

        String jbDirPath = getDirPathForJBConfigFile(project);

        try {
            String downloadUrl = getDownloadUrlByBuildEnv();
            String fileUrl = util.resolveConfigUrl(downloadUrl);

            String xClientInfo = HttpService.getXClientInfo(project);

            String remoteJson = util.fetchRemoteConfigJson(fileUrl, xClientInfo);
            ObjectMapper mapper = new ObjectMapper();

            JsonNode remoteConfig = mapper.readTree(remoteJson);

            JsonNode localConfig = util.readOrCreateLocalConfig(wingmanBaseDir + jbDirPath,
                    remoteJson, mapper);

            Path configFilePath = Paths.get(wingmanBaseDir + jbDirPath, "config.json");

            return util.updateLocalConfigIfChanged(localConfig, remoteConfig, configFilePath.toString(), mapper);

        } catch (Exception ex) {
            logger.info(
                    "error to compare llm provider details for wingman config:" + ex);
            return false;
        }
    }

}