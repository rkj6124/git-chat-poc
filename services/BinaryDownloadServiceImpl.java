package co.bito.intellij.wingman.services;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.Paths;

import java.util.function.Consumer;
import java.util.logging.Logger;

import co.bito.intellij.wingman.Constant;
import co.bito.intellij.wingman.WingmanConstants;
import co.bito.intellij.wingman.WingmanManager;
import co.bito.intellij.wingman.WingmanConstants.*;
import co.bito.intellij.wingman.WingmanMixPanelEvent;
import co.bito.intellij.wingman.modal.BitoWingmanServer;
import co.bito.intellij.wingman.modal.DownloadedBinaryStatus;

import co.bito.intellij.wingman.modal.WingmanContext;
import co.bito.intellij.wingman.modal.WingmanUserInfo;
import co.bito.intellij.wingman.util.WingmanUtil;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;

import org.cef.callback.CefQueryCallback;

import static co.bito.intellij.wingman.FileWriteOperations.clearDirectory;
import static co.bito.intellij.wingman.WingmanManager.*;
import static co.bito.intellij.wingman.services.WingmanService.*;
import static org.codehaus.plexus.util.FileUtils.deleteDirectory;

public class BinaryDownloadServiceImpl {
    private static final Logger logger = Logger.getLogger(BinaryDownloadServiceImpl.class.getName());

    /**
     * Utility function to validate the Wingman user eligibility for downloading.
     * <p>
     * - Validates if Wingman is supported for the user.
     * - Checks if the user is on a supported plan (e.g., Premium Plan).
     * - Verifies if the user is allowed to download Wingman based on their user ID
     * and workspace ID.
     * @return true if user is eligible, false otherwise
     */
    public static boolean isWingmanEnvironmentSupported(CefQueryCallback callback) {
        try {
            if (!isWingmanSupported()) {
                logger.info("Wingman environment not supported.");
                sendResponseForUnsupportedWingman(callback);
                return false;
            }
            return true;
        } catch (Exception e) {
            logger.info("Error while checking wingman environment: " + e.getMessage());
            sendResponseForUnsupportedWingman(callback);
            return false;
        }
    }

    /**
     * Common function to trigger the application logic
     * to download `BITO Wingman ` (fresh/first time) download
     * for logged-in user.
     *
     * @param isFreshDownload Flag indicating if this is a fresh download
     */
    // First, modify initiateFreshWingmanDownload to accept a callback
    public static void initiateFreshWingmanDownload(
            CefQueryCallback callbackForUI,
            Project project,
            boolean isFreshDownload,
            Consumer<Boolean> callback) {
        try {

            sendResponseForDownloadInProgress(callbackForUI, isFreshDownload);

            // Create the PID file to track download
            createPidFileBeforeDownload();

            // Run the entire process in a background thread to avoid UI blocking
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                try {
                    // Step 1: Handle fresh download (which downloads tools and binary)
                    handleFreshDownloadWithCallback(project, callbackForUI, success -> {
                        if (success) {
                            logger.info(
                                    "Fresh download completed successfully, making binary executable");

                            // Step 2: Make the downloaded binary executable
                            try {
                                makeBinaryExecutableForPremiumUser(project);

                                boolean hasWingmanSetup = setupWingmanConfiguration(project, isFreshDownload);
                                logger.info("setting up wingman configuration for a paid user " + hasWingmanSetup);

                                // Step 3: Remove the PID file to track download
                                removePidFileAfterDownload();

                                logger.info(
                                        "Wingman download and setup completed successfully");
                                if (callback != null)
                                    callback.accept(true);
                            } catch (Exception e) {
                                logger.info(
                                        "Error during binary setup: " + e.getMessage());
                                // Remove PID file even if there's an error in making binary executable
                                removePidFileAfterDownload();
                                if (callback != null)
                                    callback.accept(false);
                            }
                        } else {
                            logger.info("Fresh download failed, cleaning up");
                            // Remove PID file if download failed
                            removePidFileAfterDownload();
                            if (callback != null)
                                callback.accept(false);
                        }

                        WingmanConstants.isDownloadBinaryInProgress = false;
                    });
                } catch (Exception e) {
                    logger.info("Error in download process: " + e.getMessage());
                    // Ensure PID file is removed even if there's an exception
                    removePidFileAfterDownload();
                    if (callback != null)
                        callback.accept(false);
                }
            });
        } catch (Exception e) {
            logger.info("Error initiating fresh wingman download: " + e.getMessage());
            // Try to remove PID file if initial setup fails
            try {
                removePidFileAfterDownload();
            } catch (Exception ex) {
                logger.info("Failed to remove PID file: " + ex.getMessage());
            }
            if (callback != null) {
                callback.accept(false);
            }
                
            WingmanConstants.isDownloadBinaryInProgress = false;
        }
    }

    /**
     * Makes the downloaded binary executable and moves it to the target location
     */
    public static void makeDownloadedBinaryExecutable(Project project) {

        try {

            BinaryDownloader downloader = new BinaryDownloader();
            String baseBinaryDir = downloader.getBaseBinaryDirectory();
            Path binDirPath = Paths.get(baseBinaryDir, "bin");

            // Get download status from wingman context
            DownloadedBinaryStatus downloadStatus = WingmanContext.getDownloadStatus(project);
            boolean isDownloadSuccess = downloadStatus != null && downloadStatus.isBinaryDownloadSuccess;

            if (isDownloadSuccess) {
                // Stop existing binary
                //new WingmanManager().stopBinaryProcess(project, true);

                // Clear old binary
                clearDirectory(binDirPath.toString());

                String tempBinaryPath = downloadStatus.tempBinaryPath;
                String binaryPath = downloadStatus.binaryPath;

                // Make downloaded binary executable
                new BinaryDownloader().makeExecutable(tempBinaryPath);

                // Move the binary from temp location to actual location
                Path sourceTempBinaryPath = Path.of(tempBinaryPath);
                Path targetBinaryPath = Path.of(binaryPath);
                Files.copy(sourceTempBinaryPath, targetBinaryPath, StandardCopyOption.REPLACE_EXISTING);

                // Delete the temp dir
                Path tempDirPath = Paths.get(tempBinaryPath).getParent();
                if (tempDirPath != null) {
                    deleteDirectory(tempDirPath.toString());
                }

                logger.info(
                        "Successfully made binary executable and moved to target location");
            } else {
                logger.info(
                        "Binary download was not successful, skipping executable setup");
            }
        } catch (Exception e) {
            logger.info(
                    "Error making downloaded binary executable: " + e.getMessage());

        }

    }

    /**
     * Starts Bito Wingman after download with port conflict resolution.
     *
     * @param callback Consumer<Boolean> that will be called when the start process
     *                 completes
     *                 with true if successful, false otherwise
     */
    public void startBitoWingmanAfterDownload(Project project, Consumer<Boolean> callback) {
        // Run on a background thread to avoid UI blocking
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                DownloadedBinaryStatus downloadStatus = WingmanContext.getDownloadStatus(project);

                int port = 0;

                BitoWingmanServer savedServerInfo = WingmanContext.getServerInfo(project);

                if (downloadStatus != null && downloadStatus.isBinaryDownloadSuccess) {
                    // Get binary details
                    String[] binaryDetails = getExistingBitoWingmanBinaryDetails();
                    String baseBinaryPath = binaryDetails[0];
                    String serverHost = binaryDetails[1];

                    WingmanUtil wingmanUtil = new WingmanUtil();

                    logger.info("Starting Bito Wingman binary from path: "
                            + baseBinaryPath);

                    if (savedServerInfo != null && savedServerInfo.getPort() > 0) {
                        port = savedServerInfo.getPort();
                    } else {
                        port = wingmanUtil.getWingmanPortFromSQLiteFile(project);

                        if (port <= 0) {
                            wingmanUtil.checkIfWeNeedToChangeThePort(project);
                            port = WingmanContext.getServerInfo(project).getPort();
                        }

                        if (savedServerInfo == null) {
                            savedServerInfo = new BitoWingmanServer();
                        }

                        savedServerInfo.setPort(port);
                        WingmanContext.setServerInfo(project, savedServerInfo);
                    }

                    // Verify binary exists
                    File binaryFile = new File(baseBinaryPath);
                    if (!binaryFile.exists()) {
                        logger.info("Binary file does not exist at: " + baseBinaryPath);

                        // Check if we need to copy from temp location
                        String tempBinaryPath = downloadStatus.getTempBinaryPath();
                        if (tempBinaryPath != null && new File(tempBinaryPath).exists()) {
                            logger.info("Copying binary from temp location: "
                                    + tempBinaryPath);
                            try {
                                Files.copy(
                                        Paths.get(tempBinaryPath),
                                        Paths.get(baseBinaryPath),
                                        StandardCopyOption.REPLACE_EXISTING);
                                logger.info("Binary copied successfully to: "
                                        + baseBinaryPath);
                            } catch (Exception e) {
                                logger.info("Error copying binary: " + e.getMessage());
                                if (callback != null) {
                                    callback.accept(false);
                                }
                                return;
                            }
                        } else {
                            logger.info("Binary not found in temp location either");
                            if (callback != null) {
                                callback.accept(false);
                            }
                            return;
                        }
                    }

                    // Ensure the binary is executable
                    try {
                        new BinaryDownloader().makeExecutable(baseBinaryPath);
                        binaryFile.setExecutable(true);
                        logger.info("Ensured binary is executable: " + baseBinaryPath);
                    } catch (Exception e) {
                        logger.info(
                                "Error making binary executable: " + e.getMessage());
                    }

                    // Stop any existing process first
                    //new WingmanManager().stopBinaryProcess(project, true);

                    // Give some time for the process to fully stop
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }

                    // Reset the binary process state
                    setBinaryStarting(project,false);
                    setRestartAttempts(project, 0) ;

                    // Start the binary process directly first to ensure it works
                    boolean isStarted = false;
                    wingmanUtil = new WingmanUtil();
                    if (wingmanUtil.checkIfProcessIsNotRunning(project)) {
                        wingmanUtil.checkIfWeNeedToChangeThePort(project);
                            isStarted = new WingmanManager().startBinaryProcess(project, baseBinaryPath);
                    } else {
                        isStarted = new WingmanManager().checkWingmanProcessAlive(project);
                    }

                    logger.info("Initial binary process start result: " + isStarted);

                    if (!isStarted) {
                        logger.info(
                                "Failed to start binary process directly, will try again");

                        // Check if it's a port conflict and try to resolve
                        if (wingmanUtil.checkIfPortInUse(serverHost, port)) {
                            logger.info("Port " + port
                                    + " is still in use, trying to free it");

                            BitoWingmanServer wingmanServer = new WingmanManager().findAvailablePortNumber(project);
                            WingmanContext.setServerInfo(project, wingmanServer);
                        }

                        // Try one more time to start binary
                        if (wingmanUtil.checkIfProcessIsNotRunning(project)) {
                            wingmanUtil.checkIfWeNeedToChangeThePort(project);
                            isStarted = new WingmanManager().startBinaryProcess(project, baseBinaryPath);
                        }else {
                            isStarted = new WingmanManager().checkWingmanProcessAlive(project);
                        }
                        logger.info("Second binary process start attempt result: " + isStarted);
                    }

                    // check again if server Started
                    if (isStarted) {

                        // Update global server context
                        BitoWingmanServer wingmanServer = WingmanContext.getServerInfo(project);

                        wingmanServer.setHost(serverHost);
                        wingmanServer.setPort(port);

                        logger.info("Wingman server address: " + wingmanServer.getPort());

                        // Only start monitoring if the server is ready
                        new WingmanManager().startProcessMonitoring(baseBinaryPath, project);

                        // Call the callback with the result
                        if (callback != null) {
                            callback.accept(true);
                        }
                    } else {
                        logger.info(
                                "Failed to start Bito Wingman binary process after multiple attempts");
                        if (callback != null) {
                            callback.accept(false);
                        }
                    }
                } else {
                    logger.info(
                            "Cannot start Bito Wingman because download was not successful");
                    if (callback != null) {
                        callback.accept(false);
                    }
                }
            } catch (Exception e) {
                logger.info("Error starting Bito Wingman after download: "
                        + e.getMessage());
                e.printStackTrace(); // Print stack trace for more debugging info
                if (callback != null) {
                    callback.accept(false);
                }
            }
        });
    }

    /**
     * Checks if a port is in use.
     *
     * @param port The port number to check
     * @return true if the port is in use, false otherwise
     */
    public boolean isPortInUse(String host, int port) {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress(host, port), 1);
            return false; // Port is available
        } catch (BindException e) {
            // This is the specific exception for "port already in use"
            return true;
        } catch (IOException e) {
            // Log other types of IO exceptions
            logger.info("Error checking port " + port + ": " + e.getMessage());
            // Conservatively assume the port might be in use
            return true;
        }
    }

    /**
     * Kills any process using the specified port.
     *
     * @param port The port number
     */
    public void killProcessUsingPort(String serverHost, int port) {
        logger.info("Attempting to kill process using port " + port);

        try {
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                // Windows
                ProcessBuilder pb = new ProcessBuilder("cmd.exe", "/c",
                        "netstat -ano | findstr :" + port + " | findstr LISTENING");
                Process process = pb.start();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String[] parts = line.trim().split("\\s+");
                        if (parts.length > 4) {
                            String pid = parts[parts.length - 1];
                            logger.info("Found process " + pid + " using port " + port);
                            Runtime.getRuntime().exec("taskkill /F /PID " + pid);
                            logger.info("Killed process " + pid);
                        }
                    }
                }
            } else {
                // Mac/Linux
                ProcessBuilder pb = new ProcessBuilder("lsof", "-i", ":" + port);
                Process process = pb.start();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    boolean headerSkipped = false;
                    while ((line = reader.readLine()) != null) {
                        if (!headerSkipped) {
                            headerSkipped = true;
                            continue;
                        }
                        String[] parts = line.trim().split("\\s+");
                        if (parts.length > 1) {
                            String pid = parts[1];
                            logger.info("Found process " + pid + " using port " + port);
                            Runtime.getRuntime().exec("kill -9 " + pid);
                            logger.info("Killed process " + pid);
                        }
                    }
                }
            }

            // Wait a bit for the process to be killed
            //Thread.sleep(300);

            // Verify port is now free
            if (isPortInUse(serverHost, port)) {
                logger.info("Port " + port + " is still in use after kill attempt");
            } else {
                logger.info("Port " + port + " is now free");
            }
        } catch (Exception e) {
            logger.info("Error killing process using port " + port + ": " + e.getMessage());
        }
    }

    /**
     * Gets details of the existing Bito Wingman binary.
     *
     * @return Array containing [binaryFilePath, serverHost, serverPort]
     */
    public static String[] getExistingBitoWingmanBinaryDetails() {
        String binaryFilePath = "";
        String serverHost = "";
        String serverPort = "0";

        try {
            BinaryDownloader downloader = new BinaryDownloader();
            String baseBinaryDir = downloader.getBaseBinaryDirectory();

            Path configFilePath = Path.of(baseBinaryDir, "downloadedBinary.json");
            logger.info("Checking for existing Wingman binary details at: "
                    + configFilePath);
            // Check if config file exists
            if (Files.exists(configFilePath)) {
                // Read and parse the JSON file
                String fileContents = Files.readString(configFilePath, StandardCharsets.UTF_8);
                JsonObject wingmanDetails = new JsonParser().parse(fileContents).getAsJsonObject();

                String baseBinaryDirPath = wingmanDetails.get("binaryDir").getAsString();
                logger.info("baseBinaryDirPath details at: "
                        + baseBinaryDirPath);
                String binaryName = wingmanDetails.get("binaryName").getAsString();
                logger.info("binaryName details at: "
                        + binaryName);
                binaryFilePath = Path.of(baseBinaryDirPath, "bin", binaryName).toString();
                logger.info("binaryFilePath details at: "
                        + binaryFilePath);
                serverHost = wingmanDetails.get("host").getAsString();
                serverPort = String.valueOf(wingmanDetails.get("port").getAsInt());
            } else {
                logger.info("Wingman binary details file not found: "
                        + configFilePath);
            }
        } catch (IOException e) {
            logger.info(
                    "Error reading Wingman binary details: " + e.getMessage());
        } catch (JsonSyntaxException e) {
            logger.info("Error parsing Wingman binary details JSON: "
                    + e.getMessage());
        } catch (Exception e) {
            logger.info("Unexpected error getting Wingman binary details: "
                    + e.getMessage());
        }

        return new String[]{binaryFilePath, serverHost, serverPort};
    }

    /**
     * Common function to trigger the application logic
     * to download `Missing or Deleted BITO Wingman`
     * for logged-in user.
     *
     * @param isFreshDownload Flag indicating if this is a fresh download
     */
    public static void initiateMissingWingmanDownload(Project project, boolean isFreshDownload, CefQueryCallback callback,
                                                      Consumer<Boolean> downloadCallback) {
        try {
            // Send initial response immediately
            sendResponseForDownloadInProgress(callback, isFreshDownload);

            // Start the download process using ApplicationManager
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                try {

                    // Step 2: Create PID file to track download
                    createPidFileBeforeDownload();
                    logger.info("Created PID file before download");

                    // Step 3: Trigger download process with callback
                    triggerWingmanDownloadProcessAsync(project, callback, downloadSuccess -> {
                        if (downloadSuccess) {
                            logger.info(
                                    "Wingman download process completed successfully");

                            // Step 4: Make binary executable
                            makeDownloadedBinaryExecutableAsync(project, executableSuccess -> {
                                if (executableSuccess) {
                                    logger.info("Made downloaded binary executable");

                                    // Step 5: Remove PID file
                                    removePidFileAfterDownload();
                                    logger.info("Removed PID file after download");

                                    // Final callback with success
                                    downloadCallback.accept(true);
                                } else {
                                    logger.info("Failed to make binary executable");
                                    removePidFileAfterDownload(); // Clean up PID file even on failure
                                    downloadCallback.accept(false);
                                }
                            });
                        } else {
                            logger.info("Wingman download process failed");
                            removePidFileAfterDownload(); // Clean up PID file even on failure
                            downloadCallback.accept(false);
                        }
                    });
                } catch (Exception e) {
                    logger.info("Error in download process: " + e.getMessage());
                    try {
                        removePidFileAfterDownload(); // Clean up PID file on exception
                    } catch (Exception ex) {
                        logger.info("Error removing PID file: " + ex.getMessage());
                    }
                    downloadCallback.accept(false);
                }
            });
        } catch (Exception e) {
            logger.info(
                    "Error initiating missing Wingman download: " + e.getMessage());
            downloadCallback.accept(false);
        }
    }

    // Helper methods with callback support using ApplicationManager
    private static void triggerWingmanDownloadProcessAsync(Project project, CefQueryCallback callbackForUI, Consumer<Boolean> callback) {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                // Pass the callback to triggerWingmanDownloadProcess
                triggerWingmanDownloadProcess(project, callbackForUI, callback);
            } catch (Exception e) {
                logger.info(
                        "Error triggering Wingman download: " + e.getMessage());
                callback.accept(false);
            }
        });
    }

    private static void makeDownloadedBinaryExecutableAsync(Project project, Consumer<Boolean> callback) {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                WingmanUserInfo wingmanUser = WingmanContext.getUserInfo(project);
                boolean isPremiumPlan = wingmanUser.getIsPremiumPlan();
                String userType = isPremiumPlan ? "premium" : "free";

                logger.info("binary executable required for user type " + userType);

                if (isPremiumPlan) {
                    // Original implementation of makeDownloadedBinaryExecutable
                    makeDownloadedBinaryExecutable(project);
                }

                callback.accept(true);
            } catch (Exception e) {
                logger.info("Error making binary executable: " + e.getMessage());
                callback.accept(false);
            }
        });
    }

    public static void openBitoWingmanInNewWindow(Project project) {
        try {

            BitoWingmanServer wingmanServer = WingmanContext.getServerInfo(project);

            logger.info("open bito wingman in new window "
                    + wingmanServer.getIsServerRunning());

            Boolean isServerRunning = wingmanServer.getIsServerRunning();

            if (isServerRunning) {
                openWingmanInJBNewWindow(project, isServerRunning);
            }

        } catch (Exception ex) {
            logger.info("Error to open bito wingman in new window " + ex);
        }
    }

    public static void openWingmanForDownloadClick(Project project, CefQueryCallback callback) {
        String response = "this is response from open-bito-wingman-in-ide from IDE backend service";
        String[] binaryDetails = BinaryDownloadServiceImpl.getExistingBitoWingmanBinaryDetails();
        String baseBinaryPath = binaryDetails[0];
        String serverHost = binaryDetails[1];

        WingmanUtil wingmanUtil = new WingmanUtil();
        int serverPort = wingmanUtil.getWingmanPortFromSQLiteFile(project);
        try {

            BitoWingmanServer wingmanServer = WingmanContext.getServerInfo(project);
            wingmanServer.setHost(serverHost);
            wingmanServer.setPort(serverPort);

            WingmanContext.setServerInfo(project, wingmanServer);

            Thread.sleep(400);
            BinaryDownloadServiceImpl.openBitoWingmanInNewWindow(project);

        } catch (Exception ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            logger.info("exception caught to open wingman in separate window " + ex);
        }

        System.out.println("open-bito-wingman-in-ide response: " + response);
        callback.success(response);
    }

    public static boolean setupWingmanConfiguration(Project project, boolean isFreshDownload) {
        WingmanUserInfo wingmanUser = WingmanContext.getUserInfo(project);

        Integer wsId = wingmanUser.getWsId();
        Integer userId = wingmanUser.getUserId();
        String userToken = wingmanUser.getToken();

        Boolean premiumPlan = wingmanUser.getIsPremiumPlan();
        boolean isPremiumPlan = premiumPlan != null && premiumPlan;

        DownloadedBinaryStatus downloadStatus = WingmanContext.getDownloadStatus(project);

        try {
            BinaryToDownloadByPlatform binaryToDownloadByPlatform = BinaryDownloader.getPlatformBinaryName();
            BinaryDownloader downloader = new BinaryDownloader(binaryToDownloadByPlatform);

            BitoWingmanServer wingmanServerInfo = WingmanContext.getServerInfo(project);

            String wingmanBaseDir = downloader.getBaseBinaryDirectory();

            downloadStatus.setAvailableBinaryVersion(downloader.getAvailableBinaryVersion());
            downloadStatus.setAvailableToolsVersion(downloader.getAvailableToolsVersion());

            String bitoApiKey = "";

            if (downloadStatus == null || downloadStatus.getBaseBinaryDir() == null) {
                downloadStatus = new DownloadedBinaryStatus();
                downloadStatus.setBaseBinaryDir(wingmanBaseDir);
                downloadStatus.setBinaryName(binaryToDownloadByPlatform.getBinaryFileName());
                downloadStatus.setIsBinaryDownloaded(true);

                WingmanContext.setDownloadStatus(project, downloadStatus);
            }

            downloadStatus.setAvailableBinaryVersion(downloader.getAvailableBinaryVersion());
            downloadStatus.setAvailableToolsVersion(downloader.getAvailableToolsVersion());

            if (isPremiumPlan) {
                if (wsId > 0 && userId > 0 && userToken != null && !userToken.isEmpty()) {
                    bitoApiKey = MgmtService.getBitoAccessToken(
                            wsId,
                            userId,
                            userToken,
                            Constant.AUTOGENERATED_FOR_BITO_WINGMAN);

                    if (bitoApiKey == null || bitoApiKey.isEmpty()) {
                        return false;
                    }
                } else {
                    logger.info(
                            "could not download wingman for invalid request: wsId, userid or token");
                    return false;
                }

                BinaryDownloader.createEnvFileForWingman(project, wingmanBaseDir, bitoApiKey);

                boolean hasLatestWingmanConfig = BinaryDownloader.getLatestWingmanConfiguration(project, downloadStatus);

                JsonObject messageToSend = new JsonObject();

                if (!hasLatestWingmanConfig) {
                    messageToSend.addProperty("key", WingmanConstants.WINGMAN_DOWNLOAD_FAILED);
                    WingmanPostMessageService.notifyBitoUIForWingmanStatus(wingmanUser, messageToSend, project);
                    WingmanMixPanelEvent.callToMixPanelAboutWingman(
                            wingmanUser,
                            WingmanConstants.WINGMAN_DOWNLOAD_FAILED,
                            "ERROR",
                            "ide_window");
                    return false;
                }

                // get binary path from download binary json file
                String[] binaryDetails = getExistingBitoWingmanBinaryDetails();
                String baseBinaryPath = binaryDetails[0];

                WingmanUtil wingmanUtil = new WingmanUtil();
                boolean isStarted = false;

                if (isFreshDownload) {
                    
                    if (wingmanServerInfo == null || wingmanServerInfo.getPort() == 0
                         || wingmanServerInfo.getIsServerRunning() == null) {

                        // get port and lock the port in sql lite file
                        wingmanServerInfo = new WingmanManager().findAvailablePortNumber(project);
                        WingmanContext.setServerInfo(project, wingmanServerInfo);
                    }

                    logger.info("activating Wingman setup for premium user. Fresh setup detected. Server information: " + wingmanServerInfo);

                    // Update paths in configuration
                    new WingmanManager().updatePathsInWingmanConfig(project, wingmanServerInfo);

                    if (wingmanUtil.checkIfProcessIsNotRunning(project)) {
                        wingmanUtil.checkIfWeNeedToChangeThePort(project);
                        isStarted = new WingmanManager().startBinaryProcess(project, baseBinaryPath);
                        logger.info("activating Wingman setup for premium user. Fresh setup detected. Server started: " + isStarted);
                    }else {
                        isStarted = new WingmanManager().checkWingmanProcessAlive(project);
                    }

                    if (isStarted) {
                        logger.info("Activating Wingman setup for premium user. Fresh setup detected. Starting server monitoring...");
                        new WingmanManager().startProcessMonitoring(baseBinaryPath, project);
                    }
                }
            }

            writeToMaintainDownloadStatus(project, downloadStatus, wingmanServerInfo, true);
        } catch (Exception ex) {
            logger.info("Error to set up wingman configuration " + ex.getMessage());
            return false;
        }
        return isPremiumPlan;
    }

    public static void makeBinaryExecutableForPremiumUser(Project project) {
        WingmanUserInfo wingmanUser = WingmanContext.getUserInfo(project);

        boolean isPremiumPlan = wingmanUser.getIsPremiumPlan();
        String userType = isPremiumPlan ? "premium" : "free";

        logger.info("Make binary executable for user type " + userType);

        makeDownloadedBinaryExecutable(project);
    }

}
