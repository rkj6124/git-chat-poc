package co.bito.intellij.wingman.services;

import co.bito.intellij.wingman.WingmanConstants.*;
import co.bito.intellij.wingman.modal.BitoWingmanServer;
import co.bito.intellij.wingman.modal.WingmanContext;
import co.bito.intellij.wingman.modal.WingmanUserInfo;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;

import java.io.File;

import static co.bito.intellij.wingman.services.BinaryDownloader.getPlatformBinaryName;

public class WingmanPopUpService {

    private static final Logger logger = Logger.getInstance(WingmanService.class);

    /**
     * Function to check if the Wingman tab is opened in the JetBrains IDE
     *
     * @return true if the wingman tab is opened in the IDE or false otherwise
     */
    public boolean suggestWingmanPopUp(Project project) {
        try {
            logger.info("request received to check if Wingman popup should be shown ");
            BitoWingmanServer wingmanServer = WingmanContext.getServerInfo(project);

            if (wingmanServer.getIsServerRunning()) {
                // get all tab groups
                boolean isWingmanAlreadyOpen = isWingmanAlreadyOpen(project);
                logger.info("Wingman is currently opened and visible " + isWingmanAlreadyOpen);
                return isWingmanAlreadyOpen;
            } else {
                // if server is not running then return false, because wingman tab will not be
                // available.
                WingmanUserInfo wingmanUserInfo = WingmanContext.getUserInfo(project);
                boolean isPremiumPlan = wingmanUserInfo.getIsPremiumPlan();

                if (!isPremiumPlan) {
                    BinaryToDownloadByPlatform binaryToDownload = getPlatformBinaryName();

                    // check if BITO Wingman is downloaded
                    BinaryDownloader downloader = new BinaryDownloader(binaryToDownload);
                    String wingmanBaseDir = downloader.getBinaryPath();

                    boolean isBinaryExists = new File(wingmanBaseDir).exists();
                    if (isBinaryExists) {
                        logger.info("Binary exists, suggesting popup");
                        return true;
                    }
                }

                return false;
            }
        } catch (Exception e) {
            logger.info("Error in suggestWingmanPopUp: " + e.getMessage());
            return false;
        }
    }

    private static boolean isWingmanAlreadyOpen(Project project) {
        ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Bito Wingman");

        if (toolWindow == null) {
            System.out.println("Wingman tool window is not registered.");
            return true;
        }

        return !toolWindow.isAvailable();
    }

}
