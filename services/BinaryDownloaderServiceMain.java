/***************************************************************************
 * Copyright (C) 2021, Bito Inc - All Rights Reserved
 * Unauthorized copying of this file, its content or modification
 * via any medium is strictly prohibited.
 * Proprietary and confidential
 *
 ***************************************************************************/

package co.bito.intellij.wingman.services;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import co.bito.intellij.db.service.WingmanProcessService;
import co.bito.intellij.wingman.IDESessionContext;
import co.bito.intellij.services.FileService;
import co.bito.intellij.wingman.JetBrainsIDEInfo;
import co.bito.intellij.wingman.WingmanManager;
import co.bito.intellij.wingman.modal.BitoWingmanServer;
import co.bito.intellij.wingman.modal.WingmanContext;
import co.bito.intellij.wingman.modal.WingmanDetailsOnFile;
import co.bito.intellij.wingman.modal.WingmanUserInfo;

import com.google.gson.JsonObject;
import org.cef.callback.CefQueryCallback;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import co.bito.intellij.quey.executor.InvokeLaterExecutor;
import co.bito.intellij.wingman.WingmanConstants;
import co.bito.intellij.wingman.task.OpenWingmanInIdeTask;
import co.bito.intellij.wingman.util.WingmanUtil;
import org.json.JSONException;
import org.json.JSONObject;

import static co.bito.intellij.wingman.Constant.HTTP_STATUS_BAD_REQUEST;
import static co.bito.intellij.wingman.Constant.HTTP_STATUS_SUCCESS;
import static co.bito.intellij.wingman.services.WingmanRoutesService.handleInitializeWingman;
import static com.intellij.openapi.util.io.FileUtil.exists;

public class BinaryDownloaderServiceMain extends InvokeLaterExecutor {

    Logger LOG = Logger.getInstance(BinaryDownloaderServiceMain.class);

    private String action = "";

    public BinaryDownloaderServiceMain(String action) {
        this.action = action;
    }

    @Override
    public void invokeLater(Project project, Map<String, Object> data, CefQueryCallback callback)
            throws Exception {
        try {
            System.out.println("case to initiate action for wingman feature " + action);

            String response = ""; // this will change based on response to be sent to UI
            JsonObject jsonReturnObj = null;

            switch (action) {

                case "download-wingman":
                    callHandleInitializeWingman(project, data, callback);
                    break;

                case "open-bito-wingman-in-ide":
                    LOG.info("request received to open bito wingman in ide " + data.toString());

                    // if serverInfo is null, update it by reading sqlLite DB
                    checkAndUpdateServerInfo(project, data, callback);

                    boolean isDownloadInProgress = WingmanConstants.isDownloadBinaryInProgress;

                    LOG.info("request to process open wingman in IDE is download in progress " + isDownloadInProgress);

                    if (!isDownloadInProgress) {
                        // Check if server is running already, if not send notification and message to UI about the same
                        BitoWingmanServer serverInfo = WingmanContext.getServerInfo(project);

                        LOG.info("Received request to open Wingman in IDE. Current server status: " + serverInfo);

                        // Check if server is not running then send notification to UI
                        if(!new WingmanManager().checkWingmanProcessAlive(project)) {
                            LOG.info("Server is not already up, wait for sometime to open Wingman ");
                            WingmanService.sendResponseForServerStartInProgress(callback);

                            NotificationGroupManager.getInstance()
                                    .getNotificationGroup("BitoNotificationGroup")
                                    .createNotification("", "BITO Wingman server is starting", NotificationType.INFORMATION)
                                    .setImportant(false)
                                    .notify(project);

                            OpenWingmanInIdeTask task = new OpenWingmanInIdeTask(project, data);

                            task.waitUntilServerStartsAndOpen(project, data);
                        }

                        // If server is running then open Wingman
                        if(serverInfo != null && new WingmanManager().checkWingmanProcessAlive(project)){
                            // open Wingman
                            callback.success(String.valueOf(HTTP_STATUS_SUCCESS));
                            new OpenWingmanInIdeTask(project, data).queue();
                        }

                    } else {

                        LOG.info("launch button clicked, download is in progress, wingman will open after download");

                        WingmanService.sendResponseForDownloadInProgress(callback, false);

                        NotificationGroupManager.getInstance()
                                .getNotificationGroup("BitoNotificationGroup")
                                .createNotification("", "Downloading is in progress", NotificationType.INFORMATION)
                                .setImportant(false)
                                .notify(project);

                        OpenWingmanInIdeTask task = new OpenWingmanInIdeTask(project, data);

                        WingmanUserInfo wingmanUser = WingmanContext.getUserInfo(project);
                        String uniqueId = "";
                        boolean isPremiumUser = false; 
                        if(wingmanUser != null) {
                            uniqueId = new WingmanUtil().getUniqueId(project);
                            isPremiumUser = wingmanUser.getIsPremiumPlan();
                        }

                        LOG.info("Launch button clicked: Wingman binary download is already in progress. Will open UI upon completion for user " + uniqueId + " on premium plan: " + isPremiumUser);

                        if(isPremiumUser) {
                            task.waitUntilDownloadCompletesAndOpen(project, data);
                        }

                    }

                    break;

                case "wingman-get-current-directory":
                    response = "this is response from wingman-get-current-directory from IDE backend service";

                    System.out.println("wingman-get-current-directory response: " + response);
                    callback.success(response);
                    break;

                case "wingman-open-external-link":
                    response = "this is response from wingman-open-external-link from IDE backend service";

                    System.out.println("wingman-open-external-link response: " + response);
                    callback.success(response);
                    break;
                case "wingman-set-response-language":

                    new WingmanResponseLanguageService().updateResponseLanguage(project, data, callback);

                    break;

                case "activate-bito-wingman-for-premium-users-in-ide":
                    new WingmanActivatePremiumUserService().activateWingmanForPremiumUser(project, data, callback);
                    break;

                case "suggest-wingman-popup":
                    boolean suggestWingmanPopUp = new WingmanPopUpService().suggestWingmanPopUp(project);
                    jsonReturnObj = new JsonObject();
                    jsonReturnObj.addProperty("status", HTTP_STATUS_SUCCESS);
                    jsonReturnObj.addProperty("showWingmanPopUp", suggestWingmanPopUp);
                    callback.success(jsonReturnObj.toString());
                    break;

                case "check-wingman-download-status":
                    LOG.info("Checking wingman download status");
                    this.checkWingmanDownloadStatus(project, callback);
                    break;
            }

        } catch (Exception ex) {
            LOG.info("Error occurred to download or start bito wingman " + ex.getMessage());
        } finally {
            // reset the global variable to maintain download status to false
        }
    }

    public void callHandleInitializeWingman(Project project, Map<String, Object> data,
                   CefQueryCallback callback) {
        Boolean initializeWingman = handleInitializeWingman(project, data, callback);
        if (!initializeWingman) {
            callback.failure(HTTP_STATUS_BAD_REQUEST, "Failed to initialise the wingman.");
            return;
        }
        JsonObject jsonReturnObj = new JsonObject();
        jsonReturnObj.addProperty("status", HTTP_STATUS_SUCCESS);
        callback.success(jsonReturnObj.toString());
    }

    public void checkAndUpdateServerInfo(Project project, Map<String, Object> data,
                                         CefQueryCallback callback) {
        // based on userInfo, update serverInfo
        WingmanUserInfo userInfo = WingmanContext.getUserInfo(project);
        LOG.info(" Got user info: " + userInfo.toString());
        if (userInfo != null) {
            String uniqueId = new WingmanUtil().getUniqueId(project);
            WingmanProcessService processService = IDESessionContext.getWingmanSQLiteService();
            Map<String, Object> wingmanProcess = processService.getWingmanProcess(uniqueId);
            if (wingmanProcess != null && !wingmanProcess.isEmpty()) {
                BitoWingmanServer serverInfo = new BitoWingmanServer();
                serverInfo.setPort((Integer) wingmanProcess.get("port"));
                serverInfo.setHost((String) wingmanProcess.get("host"));
                //serverInfo.setIsServerRunning(wingmanProcess.get("status").equals("running"));
                WingmanContext.setServerInfo(project, serverInfo);
            } else {
                LOG.info("Wingman process is null");
                callHandleInitializeWingman(project, data, callback);
            }
        } else {
            LOG.info("User info is null");
            callHandleInitializeWingman(project, data, callback);
        }
    }

    public void checkWingmanDownloadStatus(Project project, CefQueryCallback callback) {
        JsonObject jsonReturnObj = null;

        WingmanUserInfo wingmanUserInfo = WingmanContext.getUserInfo(project);
        int userId = wingmanUserInfo.getUserId();
        int wsId = wingmanUserInfo.getWsId();
        String uniqueId = wsId + "-" + userId;

        JetBrainsIDEInfo jetBrainsIDEInfo = WingmanContext.getIDEInfo(project);
        String projectName = jetBrainsIDEInfo.getProjectName();
        String projectPath = jetBrainsIDEInfo.getProjectPath();

        try {
            jsonReturnObj = new JsonObject();
            jsonReturnObj.addProperty("userId", userId);
            jsonReturnObj.addProperty("projectName", projectName);
            jsonReturnObj.addProperty("projectPath", projectPath);

            BinaryDownloader downloader = new BinaryDownloader();
            String baseBinaryDir = downloader.getBaseBinaryDirectory();
            Path configFilePath = Paths.get(baseBinaryDir, "downloadedBinary.json");

            boolean isExists = exists(configFilePath.toString());
            if(!isExists) {
                jsonReturnObj.addProperty("status", WingmanConstants.WINGMAN_DOWNLOAD_IN_PROGRESS);
                callback.success(jsonReturnObj.toString());
                return;
            }
            FileService fileService = new FileService();

            String fileContents = fileService.readFile(configFilePath.toString());
            JSONObject jsonObject = new JSONObject(fileContents);

            WingmanDetailsOnFile wingmanDetails = new WingmanDetailsOnFile();
            wingmanDetails.setDownloaded(jsonObject.optBoolean("isDownloaded", false));
            wingmanDetails.setErrorMsg(jsonObject.optString("errorMsg"));
            wingmanDetails.setDownloadStartedByUserID(jsonObject.optInt("downloadStartedByUserID", 0));

            boolean isDownloaded = wingmanDetails.isDownloaded();
            String errorMsg = wingmanDetails.getErrorMsg();

            int downloadStartedByUserID = wingmanDetails.getDownloadStartedByUserID();
            jsonReturnObj.addProperty("downloadStartedByUserID", downloadStartedByUserID);


            String downloadStatus = "";

            boolean isWingmanDownloadInProgress = WingmanConstants.isDownloadBinaryInProgress;
            if (isWingmanDownloadInProgress) {
                downloadStatus = WingmanConstants.WINGMAN_DOWNLOAD_IN_PROGRESS;
            } else {
                if (isDownloaded) {
                    downloadStatus = WingmanConstants.WINGMAN_DOWNLOAD_FINISHED;
                } else {
                    if(!errorMsg.equals("")) {
                        downloadStatus = WingmanConstants.WINGMAN_DOWNLOAD_FAILED;
                    }
                }
            }

            jsonReturnObj.addProperty("status", downloadStatus);
            LOG.info("[checkWingmanDownloadStatus] Response from checkWingmanDownloadStatus:" + jsonReturnObj.toString());
            callback.success(jsonReturnObj.toString());
        } catch (JSONException e) {
            callback.failure(500, e.getMessage());
        }
    }

}
