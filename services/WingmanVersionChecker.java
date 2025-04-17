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
import java.util.logging.Logger;

import co.bito.intellij.wingman.WingmanConstants;
import co.bito.intellij.wingman.modal.DownloadedBinaryStatus;
import co.bito.intellij.wingman.modal.WingmanContext;
import co.bito.intellij.wingman.modal.WingmanLatestManifest;

import org.cef.callback.CefQueryCallback;
import org.json.JSONObject;

import com.intellij.openapi.project.Project;

import java.util.Map;

import static co.bito.intellij.services.StaticFileService.readFile;
import static co.bito.intellij.wingman.Constant.HTTP_SUCCESS_RESPONSE;
import static co.bito.intellij.wingman.WingmanManager.getBitoWingmanVersion;
import static co.bito.intellij.wingman.services.BinaryDownloader.getLatestVersionToDownload;
import static co.bito.intellij.wingman.services.BinaryDownloader.getPlatformBinaryName;
import static co.bito.intellij.wingman.services.WingmanService.*;
import static com.intellij.openapi.util.io.FileUtil.exists;

/**
 * Handles version checking and updating of Bito Wingman binary and tools
 */
public class WingmanVersionChecker {

    private static final Logger logger = Logger.getLogger(WingmanVersionChecker.class.getName());

    public WingmanVersionChecker() {
        // Default constructor
    }

    /**
     * Checks if a binary update is required by comparing installed and available versions
     *
     * @return true if an update is required, false otherwise
     */
    public boolean isBinaryUpdateRequired(Project project) {
        try {
            BinaryDownloader downloader = new BinaryDownloader();
            String baseBinaryDir = downloader.getBaseBinaryDirectory();
            String downloadHistory = Paths.get(baseBinaryDir, "downloadedBinary.json").toString();

            DownloadedBinaryStatus downloadStatus = WingmanContext.getDownloadStatus(project);
            String installedVersion = "";

            boolean isUpdateRequired = false;

            try {
                String[] versions = getLatestVersionToDownload(project);
                String availableVersion = versions[0];

                if ("0".equals(availableVersion)) {
                    logger.info("skip binary update for version " + availableVersion);
                    return false;
                }

                boolean isExists = exists(downloadHistory);

                if (isExists) {
                    String fileContents = readFile(downloadHistory);
                    JSONObject wingmanDetails = new JSONObject(fileContents);

                    String binaryDir = wingmanDetails.optString("binaryDir");
                    String binaryName = wingmanDetails.optString("binaryName");

                    if (binaryDir != null && !binaryDir.isEmpty() &&
                            binaryName != null && !binaryName.isEmpty()) {

                        String existingBinaryPath = Paths.get(binaryDir, "bin", binaryName).toString();

                        isExists = exists(existingBinaryPath);

                        if (isExists) {
                            downloadStatus.setDownloadedBinaryPath(existingBinaryPath);
                            installedVersion = getBitoWingmanVersion(project, downloadStatus);

                            WingmanContext.setDownloadStatus(project, downloadStatus);

                            int compareResult = compareVersions(
                                    installedVersion,
                                    availableVersion
                            );

                            if (compareResult == -1) {
                                // installed version is lower. -ve case
                                isUpdateRequired = true;
                            } else {
                                isUpdateRequired = false;
                            }
                        } else {
                            // binary file does not exist
                            isUpdateRequired = true;
                        }
                    }
                } else {
                    // download history does not exist
                    isUpdateRequired = true;
                }
            } catch (Exception error) {
                logger.info("Error to check is version update required " + error.getMessage());
            }

            logger.info("is binary update required " + isUpdateRequired);
            return isUpdateRequired;
        } catch (Exception e) {
            logger.severe("Error in isBinaryUpdateRequired: " + e.getMessage());
            return false;
        }
    }

    /**
     * Compares two version strings
     *
     * @param version1 First version
     * @param version2 Second version
     * @return -1 if version1 < version2, 0 if equal, 1 if version1 > version2
     */
    private int compareVersions(String version1, String version2) {
        String[] parts1 = version1.split("\\.");
        String[] parts2 = version2.split("\\.");

        int length = Math.max(parts1.length, parts2.length);

        for (int i = 0; i < length; i++) {
            int v1 = (i < parts1.length) ? Integer.parseInt(parts1[i]) : 0;
            int v2 = (i < parts2.length) ? Integer.parseInt(parts2[i]) : 0;

            if (v1 < v2) {
                return -1;
            } else if (v1 > v2) {
                return 1;
            }
        }

        return 0; // versions are equal
    }

    /**
     * Checks if the Wingman tools need to be updated
     *
     */
    public Boolean isBinaryToolsUpdateRequired(Project project) {

        String installedToolVersion = "";
        try {
            WingmanConstants.BinaryToDownloadByPlatform toolsToDownload = getPlatformBinaryName(project);

            if ("0".equals(toolsToDownload.getToolsVersionToDownload())) {
                // Do not proceed with tools download
                // There is an issue reading `manifest.json` file from server
                logger.info("Skip binary update for version " + toolsToDownload.getToolsVersionToDownload());
                return false;
            }

            BinaryDownloader toolsDownloader = new BinaryDownloader(toolsToDownload);
            String wingmanToolsDir = toolsDownloader.getWingmanToolsDir();
            boolean isToolDirExists = exists(wingmanToolsDir);

            if (isToolDirExists) {
                Path toolVersionFileToRead = Paths.get(wingmanToolsDir, "version.txt");
                String versionFileCnt = readFile(toolVersionFileToRead.toString()).trim();
                installedToolVersion = versionFileCnt;

                logger.info("Available tools version to download: " + installedToolVersion);

                if (!installedToolVersion.isEmpty()) {
                    WingmanLatestManifest wingmanManifest = WingmanConstants.wingmanLatestManifest;
                    String availableVersion = wingmanManifest.getToolsVersion();

                    int compareResult = compareVersions(installedToolVersion, availableVersion);

                    if (compareResult == -1) {
                        // Installed version is lower (-ve case)
                        return true;
                    }
                    return false;
                } else {
                    return true; // No entry found in version.txt
                }
            } else {
                return true; // Tools dir doesn't exist
            }
        } catch (Exception error) {
            logger.info("Error to update Bito Wingman tools: {0}" + error.getMessage());
        }

        return true;

    }


    /**
     * Downloads a binary update for Wingman
     *
     * @param requestBody Request object
     */
    public void downloadBinaryUpdate(Project project, CefQueryCallback callback, Map<String, Object> requestBody) {
        try {
            if (!BinaryDownloadServiceImpl.isWingmanEnvironmentSupported(callback, project)) {
                return;
            }
            if (!handleDownloadInProgress(requestBody, callback)) {
                return;
            }
            // Send success response
            JSONObject response = new JSONObject();
            response.put("status", HTTP_SUCCESS_RESPONSE);
            response.put("message", "Bito Wingman download started");
            callback.success(response.toString());
            try {
                handleFreshDownload(project, callback);
            } catch (Exception error) {
                logger.info("Error occurred to download or start bito wingman: " + error.getMessage());
            }
        } catch (Exception e) {
            logger.severe("Error in downloadBinaryUpdate: " + e.getMessage());
        }
    }


}
