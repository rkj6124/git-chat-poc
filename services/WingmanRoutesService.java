/***************************************************************************
 * Copyright (C) 2021, Bito Inc - All Rights Reserved
 * Unauthorized copying of this file, its content or modification
 * via any medium is strictly prohibited.
 * Proprietary and confidential
 *
 ***************************************************************************/

package co.bito.intellij.wingman.services;

import java.util.Map;
import java.util.function.Consumer;
import java.util.logging.Logger;

import co.bito.intellij.db.service.WingmanProcessService;
import co.bito.intellij.wingman.IDESessionContext;
import co.bito.intellij.wingman.WingmanConstants;
import co.bito.intellij.wingman.WingmanManager;
import co.bito.intellij.wingman.modal.BitoWingmanServer;
import co.bito.intellij.wingman.modal.DownloadedBinaryStatus;
import co.bito.intellij.wingman.modal.WingmanContext;
import co.bito.intellij.wingman.modal.WingmanUserInfo;
import co.bito.intellij.wingman.util.WingmanUtil;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import org.cef.callback.CefQueryCallback;

import static co.bito.intellij.wingman.Constant.*;
import static co.bito.intellij.wingman.FileWriteOperations.createBackUpFileForVsCodeConfig;
import static co.bito.intellij.wingman.WingmanManager.*;
import static co.bito.intellij.wingman.services.BinaryDownloadServiceImpl.*;
import static co.bito.intellij.wingman.services.BinaryDownloader.getPlatformBinaryName;
import static co.bito.intellij.wingman.services.WingmanPostMessageService.notifyBitoUIForWingmanStatus;
import static co.bito.intellij.wingman.services.WingmanService.*;
import static co.bito.intellij.wingman.util.WingmanUtil.extractWingmanUserInfo;

/**
 * Handler for Wingman initialization requests
 */
public class WingmanRoutesService {

    private static final Logger logger = Logger.getLogger(WingmanRoutesService.class.getName());

    /**
     * Handles the initialization request for Bito Wingman
     *
     * @param project     The project object
     * @param requestBody The request body as a Map containing user info directly
     */
    public static Boolean handleInitializeWingman(Project project, Map<String, Object> requestBody,
            CefQueryCallback callback) {

        try {
            logger.info("Request received to download wingman " + requestBody.toString());

            if (!WingmanUtil.validateWingmanUserParameters(requestBody)) {
                String message = "Invalid user parameters";
                callback.failure(HTTP_STATUS_BAD_REQUEST, message);
                return false;
            }
            WingmanUserInfo wingmanUserInfo = extractWingmanUserInfo(requestBody);
            WingmanContext.setUserInfo(project, wingmanUserInfo);

            // Validate Wingman user
            if (!isWingmanEnvironmentSupported(callback)) {
                return false;
            }

            // Check if download is already in progress
            boolean isInProgress = checkIfDownloadIsInProgressByJB();

            if (isInProgress) {

                // track that user clicked again
                WingmanContext.markDownloadClickedAgain(project);

                JsonObject messageToSend = new JsonObject();
                messageToSend.addProperty("key", WingmanConstants.WINGMAN_DOWNLOAD_IN_PROGRESS);
                notifyBitoUIForWingmanStatus(wingmanUserInfo, messageToSend, project);

                sendResponseForDownloadInProgress(callback, false);
                return false;
            }

            JsonObject jsonReturnObj = new JsonObject();
            jsonReturnObj.addProperty("status", 200);
            jsonReturnObj.addProperty("message", "Request received to initiate wingman download");
            callback.success(jsonReturnObj.toString());

            // Set response language
            String responseLanguage = wingmanUserInfo.getResponseLanguageForUser();

            // Check if download history is available
            boolean downloadHistoryAvailable = isDownloadHistoryAvailable(project);

            try {
                // Case: Fresh download of Bito Wingman and Bito Wingman Tools
                if (!downloadHistoryAvailable) {
                    boolean isFreshDownload = true;

                    // Run the entire process in a non-blocking way with callbacks
                    initiateFreshWingmanDownload(callback, project, isFreshDownload, downloadSuccess -> {
                        if (downloadSuccess) {
                            logger.info("Download completed successfully, starting Bito Wingman");

                            if (wingmanUserInfo.getIsPremiumPlan()) {
                                // Start binary only after download is success
                                new BinaryDownloadServiceImpl().startBitoWingmanAfterDownload(project, startSuccess -> {
                                    if (startSuccess) {
                                        logger.info(
                                                "Bito Wingman started successfully, opening Wingman UI when download is clicked"
                                                        + WingmanContext.wasDownloadClickedAgain(project));
                                        createBackUpFileForVsCodeConfig();

                                        // Open Wingman only after start is successful
                                        if (WingmanContext.wasDownloadClickedAgain(project)) {
                                            openWingmanForDownloadClick(project, callback);
                                            WingmanContext.clearDownloadClickedAgain(project);
                                        }

                                    } else {
                                        logger.info("Failed to start Bito Wingman after download");
                                        // Handle start failure if needed
                                    }
                                });
                            }
                        } else {
                            logger.info("Download failed, not starting Bito Wingman");
                            // Handle download failure if needed
                        }
                    });
                    return true; // Exit immediately, operations continue in background
                } else {
                    // Case: Non-fresh download / Update of Bito Wingman and Bito Wingman Tools


                    // Case: wingman binary file not found/manually deleted at `bin` folder
                    if (!isWingmanBinaryExists()) {
                        boolean isFreshDownload = true;
                        logger.info(
                                "calling case where download binary file is present but binary is missing in bin folder");
                        // Use callback pattern for download and subsequent operations
                        initiateMissingWingmanDownload(project, isFreshDownload, callback, downloadSuccess -> {
                            if (downloadSuccess) {
                                logger.info("Missing Wingman download completed successfully, starting Bito Wingman");

                                if (wingmanUserInfo.getIsPremiumPlan()) {
                                    // Start binary only after download is success
                                    new BinaryDownloadServiceImpl().startBitoWingmanAfterDownload(project, startSuccess -> {
                                        if (startSuccess) {
                                            logger.info("Bito Wingman started successfully, opening Wingman UI");

                                            // Open Wingman only after start is successful
                                            if (WingmanContext.wasDownloadClickedAgain(project)) {
                                                openWingmanForDownloadClick(project, callback);
                                                WingmanContext.clearDownloadClickedAgain(project);
                                            }

                                        } else {
                                            logger.info("Failed to start Bito Wingman after download");
                                            // Handle start failure if needed
                                        }
                                    });
                                }
                            } else {
                                logger.info("Missing Wingman download failed");
                                // Handle download failure if needed
                            }
                        });

                        return true; // Exit immediately, operations continue in background
                    } else {
                        logger.info("Bito Wingman is already installed, check for updates");

                        WingmanConstants.BinaryToDownloadByPlatform binaryToDownloadByPlatform = getPlatformBinaryName();
                        BinaryDownloader downloader = new BinaryDownloader(binaryToDownloadByPlatform);
                        DownloadedBinaryStatus downloadStatus = new DownloadedBinaryStatus();
                        downloadStatus.setAvailableBinaryVersion(downloader.getAvailableBinaryVersion());
                        downloadStatus.setAvailableToolsVersion(downloader.getAvailableToolsVersion());
                        WingmanContext.setDownloadStatus(project, downloadStatus);
                        logger.info("Value of Project is " + project);
                        // Process updates and startup using callbacks
                        processExistingWingmanInstance(project, callback, requestBody, wingmanUserInfo, success -> {
                            logger.info("Processed existing Wingman instance with success: " + success);
                            if (success && wingmanUserInfo.getIsPremiumPlan()) {

                                // Open Wingman UI after all operations complete successfully
                                logger.info("Opening Wingman UI " + WingmanContext.wasDownloadClickedAgain(project));

                                if (WingmanContext.wasDownloadClickedAgain(project)) {
                                    openWingmanForDownloadClick(project, callback);
                                    WingmanContext.clearDownloadClickedAgain(project);

                                }
                            } else {
                                if (wingmanUserInfo.getIsPremiumPlan()) {
                                    logger.info("Skip to process existing Wingman instance for free user ");
                                }
                                logger.info("Failed to process existing Wingman instance");
                                // Handle failure if needed
                            }
                        });

                        return true; // Exit immediately, operations continue in background
                    }
                }
            } catch (Exception error) {
                logger.info("Error occurred to download or start bito wingman" + error.getMessage());
                return false;
            } finally {
                logger.info("current state of download click tracker in download wingman "
                        + WingmanContext.wasDownloadClickedAgain(project));
            }

        } catch (Exception error) {
            logger.info("Error occurred to download or start bito wingman" + error.getMessage());
            return false;
        }
    }

    private static void processExistingWingmanInstance(Project project, CefQueryCallback callbackForUI,
            Map<String, Object> requestBody, WingmanUserInfo wingmanUserInfo, Consumer<Boolean> callback) {
        // Check for binary updates first
        checkForBinaryUpdateAsync(project, callbackForUI, requestBody, wingmanUserInfo, binaryUpdateResult -> {
            boolean isBinaryUpdated = binaryUpdateResult;

            // Then check for tools updates
            checkForBinaryToolsUpdateAsync(project, callbackForUI, requestBody, isBinaryUpdated, wingmanUserInfo,
                    toolsUpdateResult -> {

                        boolean isPremiumPlan = wingmanUserInfo.getIsPremiumPlan();
                        if (isPremiumPlan) {
                            WingmanUtil wingmanUtil = new WingmanUtil();

                            // Finally check if Wingman is running and start if needed
                            boolean isRunning = !wingmanUtil.checkIfProcessIsNotRunning(project);

                            WingmanConstants.BinaryToDownloadByPlatform binaryToDownloadByPlatform = BinaryDownloader
                                    .getPlatformBinaryName();
                            BinaryDownloader downloader = new BinaryDownloader(binaryToDownloadByPlatform);

                            String wingmanBaseDir = downloader.getBaseBinaryDirectory();
                            if (!isRunning) {
                                // add a delay of anything between 200ms to 700ms
                                try{
                                    Thread.sleep((long) (200 + Math.random() * 500));
                                }catch(Exception e)
                                {
                                    logger.info("Thread interrupted");
                                }
                                // config file and env file
                                wingmanUtil.createEnvFileForJBUser(project);

                                if(checkIfPortAlreadyPresentInSQLite(project).equals("initialise")) {
                                    // wait for some time for server to be started
                                    // If server is not already running
                                    logger.info("Inside WingmanRoutesService -> Server is not already up, wait for sometime to open Wingman ");
                                    WingmanService.sendResponseForServerStartInProgress(callbackForUI);

                                    callback.accept(true);
                                }

                                BitoWingmanServer wingmanServerInfo = new WingmanManager().findAvailablePortNumber(project);

                                wingmanServerInfo.setBinaryPath(wingmanBaseDir);

                                // Persist back to context to be safe
                                WingmanContext.setServerInfo(project, wingmanServerInfo);

                                new WingmanManager().updatePathsInWingmanConfig(project, wingmanServerInfo);
                                startWingmanServerAsync(project, startServerResult -> {
                                    // Callback with final result
                                    callback.accept(startServerResult);
                                });
                            } else {
                                // Wingman is already running, report success
                                callback.accept(true);
                            }

                        } else {
                            callback.accept(true);
                        }

                    });
        });
    }

    private static String checkIfPortAlreadyPresentInSQLite(Project project) {

        // check if port present return its status else return "na"
        String uniqueId = new WingmanUtil().getUniqueId(project);
        WingmanProcessService processService = IDESessionContext.getWingmanSQLiteService();
        Map<String, Object> wingmanProcess = processService.getWingmanProcess(uniqueId);
        if (wingmanProcess != null && !wingmanProcess.isEmpty()) {
            return wingmanProcess.get("status").toString();
        } else {
            return "na";
        }
    }

    private static void checkForBinaryUpdateAsync(Project project, CefQueryCallback callbackForUI, Map<String, Object> requestBody,
            WingmanUserInfo wingmanUserInfo, Consumer<Boolean> callback) {
        // Use ApplicationManager instead of new Thread
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            boolean result = checkForBinaryUpdate(project, callbackForUI, wingmanUserInfo, requestBody);
            callback.accept(result);
        });
    }

    private static void checkForBinaryToolsUpdateAsync(Project project, CefQueryCallback callbackForUI,
            Map<String, Object> requestBody, boolean isBinaryUpdated,
            WingmanUserInfo wingmanUserInfo, Consumer<Boolean> callback) {
        // Use ApplicationManager instead of new Thread
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            // Instead of calling the callback immediately, pass it to be called after
            // download completes
            checkForBinaryToolsUpdate(project, callbackForUI, requestBody, isBinaryUpdated, wingmanUserInfo, callback);
        });
    }

    private static void startWingmanServerAsync(Project project, Consumer<Boolean> callback) {
        // Use ApplicationManager instead of new Thread
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                String[] binaryDetails = getExistingBitoWingmanBinaryDetails();
                String baseBinaryPath = binaryDetails[0];
                String serverHost = binaryDetails[1];
                int serverPort = 0;

                WingmanUtil util = new WingmanUtil();

                String uniqueId = util.getUniqueIdForJB(project);

                WingmanUtil wingmanUtil = new WingmanUtil();
                WingmanProcessService processService = IDESessionContext.getWingmanSQLiteService();
                Map<String, Object> wingmanProcess = processService.getWingmanProcess(uniqueId);
                BitoWingmanServer wingmanServer = WingmanContext.getServerInfo(project);
                if (wingmanServer == null) {
                    wingmanServer = new BitoWingmanServer();
                }
                if (wingmanProcess != null) {
                    serverPort = (Integer) wingmanProcess.get("port");
                } else {
                    if (wingmanServer.getPort() > 0) {
                        serverPort = wingmanServer.getPort();
                    } else {
                        // fallback if not set
                        serverPort = 5050; 
                        wingmanUtil.lockPortNoInSQLiteFile(project, serverPort);
                    }
                }

                // Update WingmanContext with host, port, and mark server as running
                wingmanServer.setHost(serverHost);
                wingmanServer.setPort(serverPort);
                //serverInfo.setIsServerRunning(true);

                WingmanContext.setServerInfo(project, wingmanServer);

                new WingmanManager().startProcessMonitoring(baseBinaryPath, project);

                // Small delay to ensure server starts up
                //Thread.sleep(300); // already sleep is there isnide method startProcessMonitoring

                callback.accept(true);
            } catch (Exception e) {
                logger.info("Error starting Wingman server: " + e.getMessage());
                callback.accept(false);
            }
        });
    }

}