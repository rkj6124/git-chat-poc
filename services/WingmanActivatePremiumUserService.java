package co.bito.intellij.wingman.services;

import java.util.Map;

import org.cef.callback.CefQueryCallback;
import org.jetbrains.annotations.NotNull;

import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.jcef.JBCefBrowser;

import co.bito.intellij.db.service.WingmanProcessService;

import co.bito.intellij.wingman.Constant;
import co.bito.intellij.wingman.IDESessionContext;
import co.bito.intellij.wingman.WingmanConstants.*;
import co.bito.intellij.wingman.browser.WingmanBrowserManager;
import co.bito.intellij.wingman.modal.WingmanContext;
import co.bito.intellij.wingman.modal.WingmanUserInfo;
import co.bito.intellij.wingman.util.WingmanUtil;

public class WingmanActivatePremiumUserService {

    private static final Logger LOG = Logger.getInstance(WingmanActivatePremiumUserService.class);

    public void activateWingmanForPremiumUser(@NotNull Project project, @NotNull Map<String, Object> data,
            @NotNull CefQueryCallback callback) {

        LOG.info("request received to activate wingman for premium user " + data.toString());

        if (!WingmanUtil.validateWingmanUserParameters(data)) {
            String message = "Invalid user parameters";
            callback.failure(Constant.HTTP_STATUS_BAD_REQUEST, message);
            return;

        }

        WingmanUserInfo wingmanUserInfo = WingmanUtil.extractWingmanUserInfo(data);

        boolean isPremiumPlan = wingmanUserInfo.getIsPremiumPlan();
        String bitoPlanId = wingmanUserInfo.getBitoPlanId();

        WingmanContext.setUserInfo(project, wingmanUserInfo);

        boolean hasWingmanSetup = false;
        JsonObject jsonReturnObj = new JsonObject();

        if (isPremiumPlan) {
            hasWingmanSetup = BinaryDownloadServiceImpl.setupWingmanConfiguration(project, true);
            LOG.info("activate wingman configuration for a paid user is " + hasWingmanSetup);

        } else if (!isPremiumPlan && bitoPlanId.equalsIgnoreCase("bito_free")) {

            int wsId = wingmanUserInfo.getWsId();
            int userId = wingmanUserInfo.getUserId();

            String uniqueId = wsId + "-" + userId;

            // deactivate wingman

            BinaryToDownloadByPlatform binaryToDownloadByPlatform = BinaryDownloader.getPlatformBinaryName();
            BinaryDownloader downloader = new BinaryDownloader(binaryToDownloadByPlatform);

            String wingmanBaseDir = downloader.getBaseBinaryDirectory();

            String[] binaryDetails = BinaryDownloadServiceImpl.getExistingBitoWingmanBinaryDetails();

            String baseBinaryPath = binaryDetails[0];
            String serverHost = binaryDetails[1];

            WingmanUtil wingmanUtil = new WingmanUtil();
            String serverPort = String.valueOf(wingmanUtil.getWingmanPortFromSQLiteFile(project));

            LOG.info("stop Bito Wingman binary from path: " + baseBinaryPath);

            LOG.info("stop Bito Wingman binary from path: " + baseBinaryPath + " server port " + serverPort);

            // First, check if port is already in use and kill any process using it
            int port = Integer.parseInt(serverPort);
            new BinaryDownloadServiceImpl().killProcessUsingPort(serverHost,port);

            WingmanProcessService processService = IDESessionContext.getWingmanSQLiteService();
            boolean isDeleted = processService.deleteWingmanProcessById(uniqueId);

            ToolWindowManager toolWindowManager = ToolWindowManager.getInstance(project);
            ToolWindow toolWindow = toolWindowManager.getToolWindow("Bito Wingman");

            if (toolWindow == null) {
                LOG.info("Tool window is null");
                return;
            }

            toolWindow.setAvailable(false);

            JBCefBrowser browser = WingmanBrowserManager.get(project);
            if (browser != null) {
                browser.dispose();
                WingmanBrowserManager.reset(project); // cleanup
                LOG.info("Disposed Wingman browser instance.");
            }
            
            // FileService fileService = new FileService();
            // fileService.deleteDir(wingmanBaseDir);

            hasWingmanSetup = false;

        }

        jsonReturnObj.addProperty("hasWingmanSetup", hasWingmanSetup);

        callback.success(jsonReturnObj.toString());

        return;
    }

}
