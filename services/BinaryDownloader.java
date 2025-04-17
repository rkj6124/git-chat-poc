package co.bito.intellij.wingman.services;

import co.bito.intellij.wingman.WingmanConstants;
import co.bito.intellij.wingman.WingmanConstants.BinaryToDownloadByPlatform;
import co.bito.intellij.wingman.WingmanEnvConfig;
import co.bito.intellij.wingman.modal.DownloadedBinaryStatus;
import co.bito.intellij.wingman.modal.WingmanContext;
import co.bito.intellij.wingman.modal.WingmanLatestManifest;
import co.bito.intellij.wingman.util.WingmanUtil;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.WindowManager;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Logger;

import java.nio.file.Path;
import java.nio.file.Paths;

import static co.bito.intellij.services.HttpService.getXClientInfo;
import static co.bito.intellij.wingman.WingmanEnvConfig.getWingmanEnvConfig;
import static co.bito.intellij.wingman.WingmanManager.getDirPathForJBConfigFile;
import static co.bito.intellij.wingman.WingmanManager.startExtractionOfWingmanTools;
import static co.bito.intellij.wingman.services.WingmanService.makeDirectory;
import static com.intellij.openapi.util.io.FileUtil.exists;

public class BinaryDownloader {

    private final String binaryName;
    private final String httpBinaryName;
    private final String binaryToolsName;
    private final String downloadFromHTTPUrl;

    private String toolsVersion;
    private String binaryVersion;

    private static final Logger logger = Logger.getLogger(BinaryDownloader.class.getName());
    private static final String BINARY_NAME = "bitowingman";
    private static final String BINARY_TOOLS_NAME = "bitowingman-tools";

    /**
     * Constructs a BinaryDownloaderService with optional binary download
     * information
     *
     * @param binaryToDownload Optional information about the binary to download
     */
    public BinaryDownloader(BinaryToDownloadByPlatform binaryToDownload) {
        if (binaryToDownload == null) {
            binaryToDownload = new BinaryToDownloadByPlatform();
            binaryToDownload.setBinaryFileName("");
            binaryToDownload.setHttpBinaryFileName("");
            binaryToDownload.setToolsZipFileName("");
        }

        this.binaryName = binaryToDownload.getBinaryFileName() != null ? binaryToDownload.getBinaryFileName() : "";
        this.httpBinaryName = binaryToDownload.getHttpBinaryFileName() != null
                ? binaryToDownload.getHttpBinaryFileName()
                : "";
        this.binaryToolsName = binaryToDownload.getToolsZipFileName() != null ? binaryToDownload.getToolsZipFileName()
                : "";

        this.downloadFromHTTPUrl = getDownloadUrlForBinary(this.httpBinaryName);

        this.binaryVersion = binaryToDownload.getVersionToDownload();
        this.toolsVersion = binaryToDownload.getToolsVersionToDownload();
    }

    /**
     * Default constructor that initializes with null binary download information
     */
    public BinaryDownloader() {
        this(null);
    }

    /**
     * Gets the available tools version
     *
     * @return The available tools version
     */
    public String getAvailableToolsVersion() {
        return this.toolsVersion;
    }

    /**
     * Gets the available binary version
     *
     * @return The available binary version
     */
    public String getAvailableBinaryVersion() {
        return this.binaryVersion;
    }

    /**
     * Gets the download URL for the specified binary
     *
     * @param binaryToDownload The name of the binary to download
     * @return The full download URL for the binary
     */
    public static String getDownloadUrlForBinary(String binaryToDownload) {
        try {
            String baseUrl = getDownloadUrlByBuildEnv();
            // Handle empty or null binary name
            if (binaryToDownload == null || binaryToDownload.isEmpty()) {
                return "";
            }
            // Create a URL by combining base URL and binary name
            URL url = new URL(new URL(baseUrl), binaryToDownload);
            return url.toString();
        } catch (Exception e) {
            logger.info("Error creating download URL : " + e.getMessage());
            return "";
        }
    }

    /**
     * Helper function to return the base download URL for
     * the Wingman binaries / tools based on the current
     * build environment - Staging | Production | Others
     *
     * @return The base download URL as a String
     */
    public static String getDownloadUrlByBuildEnv() {
        try {
            WingmanEnvConfig wingmanEnvConfig = getWingmanEnvConfig();
            String baseUrl = wingmanEnvConfig.getDownloadUrl();

            return baseUrl;
        } catch (Exception e) {
            logger.info("Error getting download URL by build environment: " + e.getMessage());
            return "";
        }
    }

    /**
     * Determines the appropriate binary name based on the current platform and
     * architecture
     *
     * @return BinaryToDownloadByPlatform containing binary details for the current
     *         platform
     */
    public static BinaryToDownloadByPlatform getPlatformBinaryName() {

        BinaryToDownloadByPlatform downloadBinary = new BinaryToDownloadByPlatform();

        try {
            String platform = System.getProperty("os.name").toLowerCase();
            String arch = System.getProperty("os.arch").toLowerCase();

            // Map Java platform names to expected platform identifiers
            if (platform.contains("win")) {
                platform = "win32";
            } else if (platform.contains("mac") || platform.contains("darwin")) {
                platform = "darwin";
            } else if (platform.contains("linux")) {
                platform = "linux";
            }

            // Map Java architecture names to expected architecture identifiers
            if (arch.contains("amd64") || arch.contains("x86_64")) {
                arch = "x64";
            } else if (arch.contains("aarch64")) {
                arch = "arm64";
            }

            String binaryFileName = "";
            String httpBinaryFileName = "";
            String toolsZipFileName = "";
            boolean isPlatformSupported = true;

            String[] versions = getLatestVersionToDownload();
            String binaryVersion = versions[0];
            String toolsVersion = versions[1];

            if ("0".equals(binaryVersion) || "0".equals(toolsVersion)) {
                downloadBinary.setVersionToDownload(binaryVersion);
                downloadBinary.setToolsVersionToDownload(toolsVersion);
                downloadBinary.setPlatform(platform);
                return downloadBinary;
            }

            downloadBinary.setPlatform(platform);
            downloadBinary.setArch(arch);

            switch (platform) {
                case "win32":
                    binaryFileName = String.format("%s-%s-win32-%s.exe",
                            BINARY_NAME, binaryVersion, arch);
                    httpBinaryFileName = String.format("%s-%s-win32-%s.exe",
                            BINARY_NAME, binaryVersion, arch);
                    toolsZipFileName = String.format("%s-%s-win32-%s.zip",
                            BINARY_TOOLS_NAME, toolsVersion, arch);
                    break;

                case "darwin":
                    if ("arm64".equals(arch)) {
                        binaryFileName = String.format("%s-%s-darwin-arm64",
                                BINARY_NAME, binaryVersion);
                        httpBinaryFileName = String.format("%s-%s-darwin-arm64",
                                BINARY_NAME, binaryVersion);
                        toolsZipFileName = String.format("%s-%s-darwin-%s.zip",
                                BINARY_TOOLS_NAME, toolsVersion, arch);
                    } else {
                        binaryFileName = String.format("%s-%s-darwin-x64",
                                BINARY_NAME, binaryVersion);
                        httpBinaryFileName = String.format("%s-%s-darwin-x64",
                                BINARY_NAME, binaryVersion);
                        toolsZipFileName = String.format("%s-%s-darwin-x64.zip",
                                BINARY_TOOLS_NAME, toolsVersion);
                    }
                    break;

                case "linux":
                    binaryFileName = String.format("%s-%s-linux-%s",
                            BINARY_NAME, binaryVersion, arch);
                    httpBinaryFileName = String.format("%s-%s-linux-%s",
                            BINARY_NAME, binaryVersion, arch);
                    toolsZipFileName = String.format("%s-%s-linux-%s.zip",
                            BINARY_TOOLS_NAME, toolsVersion, arch);
                    break;

                default:
                    logger.info("Unsupported platform: " + platform);
                    isPlatformSupported = false;
                    break;
            }

            downloadBinary.setBinaryFileName(binaryFileName);
            downloadBinary.setHttpBinaryFileName(httpBinaryFileName);
            downloadBinary.setToolsZipFileName(toolsZipFileName);
            downloadBinary.setToolsVersionToDownload(toolsVersion);
            downloadBinary.setVersionToDownload(binaryVersion);
            downloadBinary.setIsPlatformSupported(isPlatformSupported);

        } catch (Exception e) {
            logger.info("Error determining platform binary name: " + e.getMessage());
            downloadBinary.setIsPlatformSupported(false);
        }
        return downloadBinary;
    }

    /**
     * Helper function to read the manifest file to know
     * which is the latest available version of BITO wingman or tools
     * within BITO's ecosystem.
     *
     * @return String [ ] containing [binaryVersion, toolsVersion]
     */
    public static String[] getLatestVersionToDownload() {
        try {
            String downloadUrl = getDownloadUrlByBuildEnv();

            String manifestUrl = new URL(new URL(downloadUrl), "manifest.json").toString();

            System.out.println("[getLatestVersionToDownload] manifestUrl " + manifestUrl);

            Project project = Objects.requireNonNull(WindowManager.getInstance().getIdeFrame(null).getProject());

            String xClientInfo = getXClientInfo(project);
            Response response = null;

            OkHttpClient client = new OkHttpClient();

            Request httpRequest = new Request.Builder().url(manifestUrl)
                    .addHeader("User-Agent", "Bito-intellij-extension")
                    .addHeader("X-ClientInfo", xClientInfo)
                    .addHeader("Content-Type", "application/json").build();

            response = client.newCall(httpRequest).execute();
            JsonObject manifestData = new JsonObject();

            System.out.println("[getLatestVersionToDownload] response " + response.code());

            if (response.isSuccessful()) {
                String jsonString = Objects.requireNonNull(response.body()).string();
                Gson gson = new Gson();
                manifestData = gson.fromJson(jsonString, JsonObject.class);
            }

            if (response.code() != 200) {
                logger.info(" Failed to fetch manifest. Status: " + response.code());
                return new String[] { "0", "0" };
            }

            WingmanLatestManifest latestManifest = WingmanConstants.mapToWingmanLatestManifest(manifestData);
            String latestVersion = latestManifest.getWingmanVersion();
            String toolsVersion = latestManifest.getToolsVersion();

            // Store the manifest in global info
            WingmanConstants.wingmanLatestManifest = latestManifest;

            logger.info(String.format(
                    "Latest wingman binary version retrieved: %s, latest wingman tools version retrieved: %s",
                    latestVersion, toolsVersion));

            return new String[] { latestVersion, toolsVersion };
        } catch (Exception error) {
            logger.info(" Error to fetch current version from the manifest: " + error.getMessage());
            return new String[] { "0", "0" };
        }
    }

    /**
     * Bito's wingman home directory path
     *
     * @return The base directory path for Bito Wingman binaries
     */
    public String getBaseBinaryDirectory() {
        try {
            // Get user home directory
            String homeDir = System.getProperty("user.home");
            // Join with .bitowingman directory
            Path basePath = Paths.get(homeDir, ".bitowingman");
            return basePath.toString();
        } catch (Exception e) {
            logger.info("Error getting base binary directory: " + e.getMessage());
            return "";
        }
    }

    /**
     * Downloads and extracts Wingman tools asynchronously
     *
     * @param toUpdate Whether this is an update operation
     * @param callback Callback that receives the results as String[] where:
     *                 [0] = "true" or "false" (success status)
     *                 [1] = toolsFilePath (path to the downloaded zip)
     *                 [2] = extractionPath (path where tools were extracted, empty
     *                 if failed)
     */
    public void downloadWingmanTools(Project project, Boolean toUpdate, Consumer<String[]> callback) {
        String toolsDownloadUrl = getToolsDownloadUrl();

        String wingmanBaseDir = getBaseBinaryDirectory();
        String binDir = Paths.get(wingmanBaseDir, "bin").toString();
        String toolsTempDir = binDir;
        String zipToBeExtractedAt = binDir;

        if (toUpdate != null && toUpdate) {
            String tempDir = Paths.get(wingmanBaseDir, "bin", "toolsTemp").toString();
            toolsTempDir = tempDir;
            zipToBeExtractedAt = tempDir;
        }

        try {
            // Create directory if it doesn't exist
            Files.createDirectories(Paths.get(toolsTempDir));
            String toolsFilePath = Paths.get(toolsTempDir, binaryToolsName).toString();

            String xClientInfo = getXClientInfo(project);

            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", "Bito-intellij-extension");
            headers.put("X-ClientInfo", xClientInfo);

            DownloadedBinaryStatus downloadStatus = WingmanContext.getDownloadStatus(project);
            String availableToolsVersion = downloadStatus != null
                    ? downloadStatus.getAvailableToolsVersion()
                    : "unknown";

            String progressTitle = "Downloading Tools v" + availableToolsVersion + " for Bito Wingman";

            // Start the download process with callback
            downloadToolsZipFile(project, toolsDownloadUrl, toolsFilePath, headers, progressTitle, downloadResult -> {
                if (downloadResult) {
                    logger.info("Started the extraction process...");
                    // Download succeeded, continue with extraction
                    startExtractionOfWingmanTools(
                            project,
                            true,
                            toolsFilePath,
                            toolsFilePath,
                            false,
                            extractionResult -> {
                                if (extractionResult) {
                                    logger.info("Tools downloaded and extracted successfully");
                                    // Return success result
                                    callback.accept(new String[] { "true", toolsFilePath, toolsFilePath });
                                } else {
                                    logger.info("Tools extraction failed");
                                    // Return extraction failure result
                                    callback.accept(new String[] { "false", toolsFilePath, "" });
                                }
                            });
                } else {
                    // Download failed, handle the error
                    logger.info("Tools download failed.");
                    callback.accept(new String[] { "false", toolsFilePath, "" });
                }
            });
        } catch (Exception e) {
            logger.info("Error downloading tools: " + e.getMessage());
            callback.accept(new String[] { "false", "", "" });
        }
    }

    /**
     * Downloads the tools zip file asynchronously
     *
     * @param headers       HTTP headers to include
     * @param progressTitle The title for the progress indicator
     * @param callback      Callback to be executed when download completes
     */
    private void downloadFileWithHTTP(
            String downloadFromUrl,
            String localPath,
            Map<String, String> headers,
            String progressTitle,
            Consumer<Boolean> callback) {

        Project project = Objects.requireNonNull(WindowManager.getInstance().getIdeFrame(null).getProject());
        final AtomicBoolean success = new AtomicBoolean(false);
        final AtomicReference<Exception> exceptionRef = new AtomicReference<>();

        // Run the download task in the background with a progress indicator
        ProgressManager.getInstance().run(new Task.Backgroundable(project, progressTitle, false) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(false);
                indicator.setText("Preparing download...");

                HttpURLConnection connection = null;
                Path path = Paths.get(localPath);

                try {
                    // Create parent directories if they don't exist
                    Files.createDirectories(path.getParent());

                    // Create connection
                    URL url = new URL(downloadFromUrl);
                    connection = (HttpURLConnection) url.openConnection();
                    connection.setRequestMethod("GET");

                    // Set timeout
                    connection.setConnectTimeout(15000);
                    connection.setReadTimeout(30000);

                    // Add headers
                    if (headers != null) {
                        for (Map.Entry<String, String> entry : headers.entrySet()) {
                            connection.setRequestProperty(entry.getKey(), entry.getValue());
                        }
                    }

                    // Check response code
                    int responseCode = connection.getResponseCode();
                    if (responseCode != HttpURLConnection.HTTP_OK) {
                        logger.info("HTTP error in download file with HTTP: " + responseCode + " "
                                + connection.getResponseMessage());
                        throw new IOException("HTTP error: " + responseCode);
                    }

                    // Record start time for speed calculation
                    final long startTime = System.currentTimeMillis();

                    // Get content length for progress calculation
                    final int contentLength = connection.getContentLength();
                    int totalBytesRead = 0;

                    // Open streams and download file
                    try (InputStream inputStream = connection.getInputStream();
                            FileOutputStream outputStream = new FileOutputStream(localPath)) {

                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        long lastUpdateTime = System.currentTimeMillis();

                        // Read and write data in chunks
                        while ((bytesRead = inputStream.read(buffer)) != -1) {
                            outputStream.write(buffer, 0, bytesRead);
                            totalBytesRead += bytesRead;

                            // Update progress indicator
                            if (contentLength > 0) {
                                double fraction = (double) totalBytesRead / contentLength;
                                indicator.setFraction(fraction);

                                // Update text less frequently to avoid UI freezes
                                long currentTime = System.currentTimeMillis();
                                if (currentTime - lastUpdateTime > 200) { // Update every 200ms
                                    lastUpdateTime = currentTime;

                                    // Calculate download speed
                                    long elapsedTime = currentTime - startTime;
                                    double speedKBps = 0;
                                    if (elapsedTime > 0) {
                                        speedKBps = (totalBytesRead / 1024.0) / (elapsedTime / 1000.0);
                                    }

                                    int percentage = (int) (fraction * 100);
                                    indicator.setText(String.format("Downloading Bito Wingman binary: %d%% (%.1f KB/s)",
                                            percentage, speedKBps));

                                }
                            } else {
                                // If content length is unknown, show indeterminate progress
                                indicator.setIndeterminate(true);
                                indicator.setText("[downloadFileWithHTTP] Downloading Bito Wingman binary... ("
                                        + (totalBytesRead / 1024) + " KB)");
                            }
                        }

                        logger.info("Binary file downloaded to: " + localPath);
                        success.set(true);
                    }
                } catch (Exception e) {
                    logger.info("Download binary failed: " + e.getMessage());
                    exceptionRef.set(e);

                    // Try to delete the partial file if it exists
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ex) {
                        // Ignore cleanup errors
                    }
                } finally {
                    if (connection != null) {
                        connection.disconnect();
                    }
                }
            }

            @Override
            public void onSuccess() {
                if (success.get()) {
                    // Notification notification = NotificationGroupManager.getInstance()
                    // .getNotificationGroup("BitoNotificationGroup")
                    // .createNotification("Download Complete", "Bito Wingman binary downloaded
                    // successfully",
                    // NotificationType.INFORMATION);
                    // notification.setImportant(false);
                    // notification.notify(project);
                }
                // Call the callback with the result
                callback.accept(success.get());
            }

            @Override
            public void onThrowable(@NotNull Throwable error) {
                Exception e = exceptionRef.get();
                String errorMessage = e != null ? e.getMessage() : error.getMessage();

                NotificationGroupManager.getInstance()
                        .getNotificationGroup("BitoNotificationGroup")
                        .createNotification("Download Failed", errorMessage, NotificationType.ERROR)
                        .notify(project);

                // Call the callback with false in case of error
                callback.accept(false);
            }
        });
    }

    private void downloadToolsZipFile(
            Project project,
            String downloadUrl,
            String destinationPath,
            Map<String, String> headers,
            String progressTitle,
            Consumer<Boolean> callback) {

        final AtomicBoolean success = new AtomicBoolean(false);
        final AtomicReference<Exception> exceptionRef = new AtomicReference<>();

        // Run the download task in the background with a progress indicator
        ProgressManager.getInstance().run(new Task.Backgroundable(project, progressTitle, false) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(false);
                indicator.setText("Preparing download...");

                HttpURLConnection connection = null;
                Path path = Paths.get(destinationPath);

                try {
                    // Create connection
                    URL url = new URL(downloadUrl);
                    connection = (HttpURLConnection) url.openConnection();
                    connection.setRequestMethod("GET");

                    // Set timeout
                    connection.setConnectTimeout(15000);
                    connection.setReadTimeout(30000);

                    // Add headers
                    if (headers != null) {
                        for (Map.Entry<String, String> entry : headers.entrySet()) {
                            connection.setRequestProperty(entry.getKey(), entry.getValue());
                        }
                    }

                    // Check response code
                    int responseCode = connection.getResponseCode();
                    if (responseCode != HttpURLConnection.HTTP_OK) {
                        logger.info("HTTP error: " + responseCode + " response msg" + connection.getResponseMessage());
                        throw new IOException("HTTP error: " + responseCode);
                    }

                    // Record start time for speed calculation
                    final long startTime = System.currentTimeMillis();

                    // Get content length for progress calculation
                    final int contentLength = connection.getContentLength();
                    int totalBytesRead = 0;

                    // Create parent directories if they don't exist
                    Files.createDirectories(path.getParent());

                    // Open streams and download file
                    try (InputStream inputStream = connection.getInputStream();
                            FileOutputStream outputStream = new FileOutputStream(destinationPath)) {

                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        long lastUpdateTime = System.currentTimeMillis();

                        // Read and write data in chunks
                        while ((bytesRead = inputStream.read(buffer)) != -1) {
                            outputStream.write(buffer, 0, bytesRead);
                            totalBytesRead += bytesRead;

                            // Update progress indicator
                            if (contentLength > 0) {
                                double fraction = (double) totalBytesRead / contentLength;
                                indicator.setFraction(fraction);

                                // Update text less frequently to avoid UI freezes
                                long currentTime = System.currentTimeMillis();
                                if (currentTime - lastUpdateTime > 200) { // Update every 200ms
                                    lastUpdateTime = currentTime;

                                    // Calculate download speed
                                    long elapsedTime = currentTime - startTime;
                                    double speedKBps = 0;
                                    if (elapsedTime > 0) {
                                        speedKBps = (totalBytesRead / 1024.0) / (elapsedTime / 1000.0);
                                    }

                                    int percentage = (int) (fraction * 100);
                                    indicator.setText(String.format("Downloading wingman tools: %d%% (%.1f KB/s)",
                                            percentage, speedKBps));

                                }
                            } else {
                                // If content length is unknown, show indeterminate progress
                                indicator.setIndeterminate(true);
                                indicator.setText("Downloading wingman tools... (" + (totalBytesRead / 1024) + " KB)");
                            }
                        }

                        logger.info("ZIP file downloaded to: " + destinationPath);
                        success.set(true);
                    }
                } catch (Exception e) {
                    logger.info("Download Tools failed: " + e.getMessage());
                    exceptionRef.set(e);

                    // Try to delete the partial file if it exists
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ex) {
                        // Ignore cleanup errors
                    }
                } finally {
                    if (connection != null) {
                        connection.disconnect();
                    }
                }
            }

            @Override
            public void onSuccess() {
                if (success.get()) {
                    // TODO:
                }
                // Call the callback with the result
                callback.accept(success.get());
            }

            @Override
            public void onThrowable(@NotNull Throwable error) {
                Exception e = exceptionRef.get();
                String errorMessage = e != null ? e.getMessage() : error.getMessage();

                NotificationGroupManager.getInstance()
                        .getNotificationGroup("BitoNotificationGroup")
                        .createNotification("Download Failed", errorMessage, NotificationType.ERROR)
                        .notify(project);

                // Call the callback with false in case of error
                callback.accept(false);
            }
        });
    }

    private String getToolsDownloadUrl() {
        try {
            String baseUrl = getDownloadUrlByBuildEnv();
            // Make sure baseUrl ends with a slash for proper URL resolution
            if (!baseUrl.endsWith("/")) {
                baseUrl += "/";
            }
            // Create the complete URL by resolving the tools name against the base URL
            URL url = new URL(new URL(baseUrl), this.binaryToolsName);
            return url.toString();
        } catch (Exception e) {
            logger.info("Error creating tools download URL: " + e.getMessage());
            return "";
        }
    }

    public void downloadBinary(Project project, String fromWhereToDownload, Consumer<DownloadedBinaryStatus> callback) {
        if (fromWhereToDownload == null) {
            fromWhereToDownload = "";
        }

        String binaryPath = getBinaryPath();
        String tempBinaryPath = getTempBinaryPath();
        String baseBinaryDir = getBaseBinaryDirectory();

        String platform = System.getProperty("os.name").toLowerCase() + "-" +
                System.getProperty("os.arch").toLowerCase();

        String whichBinaryName = "";
        final String finalFromWhereToDownload = fromWhereToDownload;

        try {
            // Ensure storage directories exist
            Files.createDirectories(Paths.get(binaryPath).getParent());
            Files.createDirectories(Paths.get(tempBinaryPath).getParent());

            if ("http-bucket".equals(finalFromWhereToDownload)) {
                // Download binary from http bucket
                whichBinaryName = this.httpBinaryName;

                String url = this.downloadFromHTTPUrl;

                String xClientInfo = getXClientInfo(project);

                Map<String, String> headers = new HashMap<>();
                headers.put("User-Agent", "Bito-intellij-extension");
                headers.put("X-ClientInfo", xClientInfo);

                final String finalWhichBinaryName = whichBinaryName;

                DownloadedBinaryStatus status = WingmanContext.getDownloadStatus(project);
                String availableBinaryVersion = status.getAvailableBinaryVersion();

                // Download the file with callback
                downloadFileWithHTTP(
                        url,
                        tempBinaryPath,
                        headers,
                        "Downloading Bito Wingman v" + availableBinaryVersion,
                        isDownloadSuccess -> {
                            if (isDownloadSuccess) {
                                logger.info("Bito Wingman Download success " + isDownloadSuccess);

                                status.setBinaryPath(binaryPath);
                                status.setTempBinaryPath(tempBinaryPath);
                                status.setBinaryDownloadSuccess(true);
                            }

                            status.setDownloadedBinaryPath(binaryPath);
                            status.setIsBinaryDownloaded(isDownloadSuccess);
                            status.setBaseBinaryDir(baseBinaryDir);
                            status.setErrorMsg(isDownloadSuccess ? "" : "Download failed");
                            status.setPlatform(platform);
                            status.setBinaryName(finalWhichBinaryName);

                            // Call the callback with the status
                            callback.accept(status);
                        });
                return;
            }

            // If we reach here with no download, return a default status
            DownloadedBinaryStatus defaultStatus = WingmanContext.getDownloadStatus(project);
            defaultStatus.setDownloadedBinaryPath(binaryPath);
            defaultStatus.setIsBinaryDownloaded(false);
            defaultStatus.setBaseBinaryDir(baseBinaryDir);
            defaultStatus.setErrorMsg("Unsupported download source");
            defaultStatus.setPlatform(platform);
            defaultStatus.setBinaryName(whichBinaryName);

            callback.accept(defaultStatus);

        } catch (Exception e) {
            logger.info("Failed to download BITO wingman: " + e.getMessage());

            // Create error status
            DownloadedBinaryStatus errorStatus = WingmanContext.getDownloadStatus(project);
            errorStatus.setDownloadedBinaryPath(binaryPath);
            errorStatus.setIsBinaryDownloaded(false);
            errorStatus.setErrorMsg(e.getMessage());
            errorStatus.setBaseBinaryDir(baseBinaryDir);
            errorStatus.setPlatform(platform);
            errorStatus.setBinaryName(whichBinaryName);

            callback.accept(errorStatus);
        }
    }

    /**
     * Gets the path to the Wingman binary.
     *
     * @return The path to the binary file
     */
    public String getBinaryPath() {
        // Use Bito's default bin directory
        String homeDir = System.getProperty("user.home");
        return Paths.get(homeDir, ".bitowingman", "bin", this.binaryName).toString();
    }

    /**
     * Gets the path to the temporary binary location.
     *
     * @return The path to the temporary binary file
     */
    public String getTempBinaryPath() {
        // Use Bito's default bin directory
        String homeDir = System.getProperty("user.home");
        return Paths.get(homeDir, ".bitowingman", "temp", this.binaryName).toString();
    }

    /**
     * Makes a file executable.
     *
     * @param filePath The path to the file
     * @throws IOException If an I/O error occurs
     */
    public void makeExecutable(String filePath) throws IOException {
        // Make binary executable on Unix-like systems
        WingmanUtil.setFilePermissionsSafely(Paths.get(filePath));
    }

    /**
     * Downloads the latest Wingman configuration file from the server.
     *
     * @param downloadStatus The download status containing the base directory
     * @return true if configuration was downloaded successfully, false otherwise
     */
    public static boolean getLatestWingmanConfiguration(Project project, DownloadedBinaryStatus downloadStatus) {
        try {
            String downloadUrl = getDownloadUrlByBuildEnv();
            String fileUrl;

            try {
                URL baseUrl = new URL(downloadUrl);
                URL resolvedUrl = new URL(baseUrl, "config.json");
                fileUrl = resolvedUrl.toString();
            } catch (MalformedURLException e) {
                logger.info("Invalid URL for config file" + e);
                return false;
            }

            String xClientInfo = getXClientInfo(project);

            String saveFileAt = Paths
                    .get(downloadStatus.getBaseBinaryDir() + getDirPathForJBConfigFile(project), "config.json").toString();

            Path dir = Paths.get(saveFileAt).getParent();

            // Create directory if it doesn't exist
            Files.createDirectories(dir);

            // Set up connection
            HttpURLConnection connection = (HttpURLConnection) new URL(fileUrl).openConnection();
            connection.setRequestMethod("GET");

            // Add headers
            connection.setRequestProperty("User-Agent", "Bito-intellij-extension");
            connection.setRequestProperty("X-ClientInfo", xClientInfo);
            connection.setRequestProperty("Content-Type", "application/json");

            // Check response code
            int status = connection.getResponseCode();

            if (status != 200) {
                logger.info("Failed to download wingman config file. Status: " + status);
                return false;
            }

            // Create a file output stream
            try (InputStream inputStream = connection.getInputStream();
                    FileOutputStream outputStream = new FileOutputStream(saveFileAt)) {

                // Buffer for reading data
                byte[] buffer = new byte[8192];
                int bytesRead;

                // Read from input and write to output
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }

                // logger.debug("Config file successfully downloaded to: " + saveFileAt);
                return true;
            }
        } catch (Exception e) {
            logger.info("Error downloading wingman configuration file: " + e.getMessage());
            return false;
        }
    }

    /**
     * Helper function to create `env` file into `<user-home-dir>\.bitowingman`.
     * <p>
     * This function creates env file and writes below to the file:
     * - BITO_API_KEY : user specific Bito API Key.
     * - BITO_API_URL: base url for BITO AI backend services.
     * - BITO_TRACKING_URL: base url for BITO Wingman tracking services.
     * <p>
     * refer: `.bitowingman\env`
     *
     * @param baseDirPath the base directory path where the `.env` file is located
     * @param bitoApiKey  user specific BITO API Key
     */
    // public static void createEnvFileForWingman(Project project, String baseDirPath, String bitoApiKey) {
    //     try {
    //         // Ensure base directory exists
    //         boolean isBaseDirExists = exists(baseDirPath);
    //         if (!isBaseDirExists) {
    //             makeDirectory(baseDirPath);
    //         }

    //         WingmanEnvConfig wingmanEnvConfig = getWingmanEnvConfig();
    //         String bitoApiUrl = wingmanEnvConfig.getApiUrl();
    //         String bitoTrackingUrl = wingmanEnvConfig.getTrackingUrl();

    //         // Path for the main env file
    //         String fileToWriteOnPath = Paths.get(baseDirPath, "env").toString();

    //         // Path for the JB env file
    //         String jbConfigSubPath = getDirPathForJBConfigFile(project); // returns "/jb/3016-4499"
    //         String fileToWriteOnPathForJB = Paths.get(baseDirPath + jbConfigSubPath, "env").toString();

    //         // Write to .bitowingman/env
    //         try (BufferedWriter writer = Files.newBufferedWriter(Paths.get(fileToWriteOnPath),
    //                 StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
    //             logger.info("path for file is : " + fileToWriteOnPath);
    //             writer.write("BITO_API_URL=" + bitoApiUrl + "\n");
    //             writer.write("BITO_TRACKING_URL=" + bitoTrackingUrl + "\n");
    //             writer.write("BITO_API_KEY=" + bitoApiKey);

    //             try {
    //                 Set<PosixFilePermission> permissions = new HashSet<>();
    //                 permissions.add(PosixFilePermission.OWNER_READ);
    //                 permissions.add(PosixFilePermission.OWNER_WRITE);
    //                 Files.setPosixFilePermissions(Paths.get(fileToWriteOnPath), permissions);
    //             } catch (UnsupportedOperationException e) {
    //                 logger.info("POSIX file permissions not supported on this file system");
    //             }

    //             logger.info("File successfully written to " + fileToWriteOnPath);
    //         }

    //         // Ensure .bitowingman/jb/<folder> directory exists
    //         Path jbEnvPath = Paths.get(fileToWriteOnPathForJB);
    //         Path jbParentDir = jbEnvPath.getParent();
    //         if (!Files.exists(jbParentDir)) {
    //             Files.createDirectories(jbParentDir);
    //         }

    //         // Write to .bitowingman/jb/<folder>/env
    //         try (BufferedWriter writer = Files.newBufferedWriter(jbEnvPath,
    //                 StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
    //             logger.info("path for JB file is : " + fileToWriteOnPathForJB);
    //             writer.write("BITO_API_URL=" + bitoApiUrl + "\n");
    //             writer.write("BITO_TRACKING_URL=" + bitoTrackingUrl + "\n");
    //             writer.write("BITO_API_KEY=" + bitoApiKey);

    //             try {
    //                 Set<PosixFilePermission> permissions = new HashSet<>();
    //                 permissions.add(PosixFilePermission.OWNER_READ);
    //                 permissions.add(PosixFilePermission.OWNER_WRITE);
    //                 Files.setPosixFilePermissions(jbEnvPath, permissions);
    //             } catch (UnsupportedOperationException e) {
    //                 logger.info("POSIX file permissions not supported on this file system");
    //             }

    //             logger.info("File successfully written to " + fileToWriteOnPathForJB);
    //         }

    //     } catch (Exception error) {
    //         logger.info("Error writing wingman env to file: " + error.getMessage());
    //     }
    // }

    public static void createEnvFileForWingman(Project project, String baseDirPath, String bitoApiKey) {
        try {
            // Ensure base directory exists
            boolean isBaseDirExists = exists(baseDirPath);
            if (!isBaseDirExists) {
                makeDirectory(baseDirPath);
            }
    
            WingmanEnvConfig wingmanEnvConfig = getWingmanEnvConfig();
            String bitoApiUrl = wingmanEnvConfig.getApiUrl();
            String bitoTrackingUrl = wingmanEnvConfig.getTrackingUrl();
    
            // Path for the main env file
            String fileToWriteOnPath = Paths.get(baseDirPath, "env").toString();
    
            // Path for the JB env file
            String jbConfigSubPath = getDirPathForJBConfigFile(project); // returns "/jb/3016-4499"
            String fileToWriteOnPathForJB = Paths.get(baseDirPath + jbConfigSubPath, "env").toString();
    
            // Write to .bitowingman/env
            try (BufferedWriter writer = Files.newBufferedWriter(Paths.get(fileToWriteOnPath),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                logger.info("path for file is : " + fileToWriteOnPath);
                writer.write("BITO_API_URL=" + bitoApiUrl + "\n");
                writer.write("BITO_TRACKING_URL=" + bitoTrackingUrl + "\n");
                writer.write("BITO_API_KEY=" + bitoApiKey);
    
                // Use the shared POSIX-safe utility
                WingmanUtil.setFilePermissionsSafely(Paths.get(fileToWriteOnPath));
    
                logger.info("File successfully written to " + fileToWriteOnPath);
            }
    
            // Ensure .bitowingman/jb/<folder> directory exists
            Path jbEnvPath = Paths.get(fileToWriteOnPathForJB);
            Path jbParentDir = jbEnvPath.getParent();
            if (!Files.exists(jbParentDir)) {
                Files.createDirectories(jbParentDir);
            }
    
            // Write to .bitowingman/jb/<folder>/env
            try (BufferedWriter writer = Files.newBufferedWriter(jbEnvPath,
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                logger.info("path for JB file is : " + fileToWriteOnPathForJB);
                writer.write("BITO_API_URL=" + bitoApiUrl + "\n");
                writer.write("BITO_TRACKING_URL=" + bitoTrackingUrl + "\n");
                writer.write("BITO_API_KEY=" + bitoApiKey);
    
                // Use the shared POSIX-safe utility
                WingmanUtil.setFilePermissionsSafely(jbEnvPath);
    
                logger.info("File successfully written to " + fileToWriteOnPathForJB);
            }
    
        } catch (Exception error) {
            logger.info("Error writing wingman env to file: " + error);
        }
    }
    

    /**
     * Gets the directory path for Wingman tools
     *
     * @return The path to the Wingman tools directory
     */
    public String getWingmanToolsDir() {
        // Use Bito's default bin directory
        String homeDir = System.getProperty("user.home");
        return Paths.get(homeDir, ".bitowingman", "bin", "tools").toString();
    }

}
