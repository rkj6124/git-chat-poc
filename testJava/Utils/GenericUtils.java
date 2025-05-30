package Utils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.IOUtils;
import org.jetbrains.annotations.NotNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.diff.editor.ChainDiffVirtualFile;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationInfo;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.ui.UIUtil;

import co.bito.intellij.bean.EditorInfo;
import co.bito.intellij.bean.SupportedBitoVersion;
import co.bito.intellij.bean.UserLoginStatus;
import co.bito.intellij.services.EditorService;
import co.bito.intellij.services.HttpService;
import co.bito.intellij.services.UserInfoService;
import co.bito.intellij.setting.Constant;
import java.nio.file.FileSystems;
import java.nio.file.Path;

public class GenericUtils {

    public static String PROCESS_MONITORING_FILE_PATH = "";
    public static String LOCK_FILE_PATH = "";

    public static String getMD5(String filePath) {
        File file = new File(filePath);
        String md5 = null;
        FileInputStream fileInputStream = null;
        try {
            fileInputStream = new FileInputStream(file);
            // md5Hex converts an array of bytes into an array of characters representing the hexadecimal values of each byte in order.
            // The returned array will be double the length of the passed array, as it takes two characters to represent any given byte.
            md5 = DigestUtils.md5Hex(IOUtils.toByteArray(fileInputStream));
            fileInputStream.close();
        } catch (IOException e) {
            e.printStackTrace();
            return "error";
        }
        return md5;
    }

    public static String[] getClodFileMeta(String vfFilePath)
    {
        File file = new File(vfFilePath);
        if(file.exists())
        {
            return file.getParentFile().getName().split("_");
        }
        else
            return new String[]{"vfFilePath"};
    }
    public static boolean isCloudFile(VirtualFile vf)
    {
        String []paramValues = getClodFileMeta(vf.getPath());
        return paramValues !=null && paramValues.length == 6;
    }
    public static boolean getCloudFileData(Project project, JsonObject data, VirtualFile vf) {

        String []paramValues = getClodFileMeta(vf.getPath());
        boolean returnValue = false;

        // In case data is null create a new json object and send data in it.
        if(data == null ) {
            data = new JsonObject();
        }
        //c://cxc/xcx/temp/1_2_3_4_5_6/filename.xyz
        // 0- assetId, 1- artifactSourceId/cid, 2 - discussionId/chid , 3- wid, 4-wgid, 5 - artifactId
        if(paramValues !=null && paramValues.length == 6) {
            data.addProperty("cloudFile", "y");
            data.addProperty("assetId", paramValues[0]);
            data.addProperty("cid", paramValues[1]); // keeping cid - old param also there for backward compatibility, unless UI old code is removed. 
            data.addProperty("artifactSourceId", paramValues[1]); // add artifactSourceId as cid which is now called as artifact Source Id
            data.addProperty("discussionId", paramValues[2]);// pass as discussion id for UI
            data.addProperty("wid", paramValues[3]);
            data.addProperty("wgid", paramValues[4]);
            data.addProperty("artifactId", paramValues[5]);
            returnValue = true;

        } else {
            data.addProperty("cloudFile", "n");
            returnValue = false;
        }
        //Send this extra param for diff view
        try {
            EditorService editorService = project.getService(EditorService.class);
            if (editorService.getActiveEditorWindow().getSelectedFile() instanceof ChainDiffVirtualFile) {
                data.addProperty("isDiffView", "y");
            } else {
                data.addProperty("isDiffView", "n");
            }
        }catch (Exception e) {
            e.printStackTrace();
            data.addProperty("isDiffView", "n");
        }
        return returnValue;
    }

    public static String getVersionedFileName (String fileName, String fileVersion) {
        if(fileVersion == null) return fileName;

        String finalFileName = "";
        int lastIndexOf = fileName.lastIndexOf( "." );
        if(lastIndexOf != -1) {
            String fileNameWithoutExtension = fileName.substring(0, lastIndexOf);
            String extension = fileName.substring(lastIndexOf + 1);
            // add version number in the file name
            finalFileName = fileNameWithoutExtension + "_" + fileVersion;
            //Add extension in the file name
            finalFileName = finalFileName + "." + extension;
        } else {
            finalFileName = fileName + "_" + fileVersion;;
        }
        return finalFileName;
     }

     public static String getTempFileDirectoryName(HashMap requestMap) {
         String tempDirectoryName = null;

         return tempDirectoryName = requestMap.get("id").toString() + "_" +
                 requestMap.get("cid").toString() + "_" +
                 requestMap.get("chid").toString() + "_" +
                 requestMap.get("wid").toString() + "_" +
                 requestMap.get("wgid").toString()+ "_" +
                 requestMap.get("artifactId").toString();
     }

     public static int generateRandomInt(int max, int min) {
        return Double.valueOf(Math.random() * (max - min + 1) + min).intValue();
     }

     public static Map createMapForInputParams(String tokenURL, String unInstallURL, String userLoginStatusURL, String navigatorInfo,String clientUrl) {
        Map<String, String> map = new HashMap<>();
        map.put("tokenURL", tokenURL);
        map.put("unInstallURL", unInstallURL);
        map.put("userLoginStatusURL", userLoginStatusURL);
        map.put("navigatorInfo", navigatorInfo);
        map.put("clientUrl", clientUrl);

        return map;
     }

     public static String getJetBrainsIDEInfo() {
        String ideVersion = null;
        try {
            ideVersion = ApplicationInfo.getInstance().getFullApplicationName();
        } catch (Exception e) {
            System.out.println("Error getting IDE Version" + e);
            ideVersion = "NA";
        }
        return ideVersion;
     }

     public static String getJetBrainsParentIDEName() {
        return "JB";
     }

    public static int getFontSize() {
        return UIUtil.getFont(UIUtil.FontSize.NORMAL, null).getSize();
    }

    public static String getFontFamily() {
        return UIUtil.getFont(UIUtil.FontSize.NORMAL, null).getFontName();
    }

    public static String getTheme() {
        String theme = "light";
        if(UIUtil.isUnderDarcula()) {
            theme = "dark";
        }
        return theme;
    }

    public static String getBasicInfoForLogin(Project project) {
        UserLoginStatus userLoginStatus = FileStorageHandler.getUUID();

        EditorInfo editorInfo = new EditorInfo(Constant.IDE_VERSION,
                userLoginStatus.getuId(),
                userLoginStatus.getStatus(),
                Constant.BITO_VERSION);

        // set Font size, font family and theme information
        editorInfo.setFontSize(getFontSize());
        editorInfo.setFontFamily(getFontFamily());
        editorInfo.setTheme(getTheme());
        System.out.println("EditorInfo =" + editorInfo);

        // set this for future use also
        project.getService(UserInfoService.class).setEditorInfo(editorInfo);

        return "productName=" + editorInfo.getProductName() +
                "&parentIdeName=" + getJetBrainsParentIDEName() +
                "&uId=" + editorInfo.getuId() +
                "&userLoginStatus=" + editorInfo.getUserLoginStatus() +
                "&bitoVersion=" + editorInfo.getBitoVersion() +
                "&theme=" + editorInfo.getTheme() +
                "&fontSize=" + editorInfo.getFontSize() +
                "&fontFamily=" + editorInfo.getFontFamily();
    }

    public static String getEnvironment() {
        try {
            if(Constant.ENVIRONMENT.equals("staging")) {
                return "staging";
            } else {
                return "PROD";
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return "staging";
    }

    /**
     * Helper method to fetch supported BITO version information
     * within BITO system (database)
     * 
     * @return {@link SupportedBitoVersion} containing the fetched
     *         version information.
     */
    public static SupportedBitoVersion getPluginVersionInfoFromDB() {
        return HttpService.getSupportedBitoVersionInfo();
    }

    /**
     * Retrieves the version of the Bito plugin that is currently
     * installed in the user's IDE.
     * 
     * <p>
     * Method attempts to obtain the version of the Bito plugin 
     * using the {@link PluginManagerCore}.
     * </p>
     * 
     * <p>
     * <li>First try: use the modern API PluginManagerCore.getPlugin(PluginId.findId(...)).
     * <li>Second try: falls back to an older API PluginManagerCore.getPlugin(PluginId.getId(...)).<li>
     * <li> Default: predefined constant version specified in Constant.BITO_VERSION_STATIC.</li>
     * </p>
     * 
     */
    public static String getBitoVersion() {
        String bitoVersion = null;
        try {
            bitoVersion = fetchBitoVersion();

        } catch (Exception e) {
            System.out.println("Error getting Bito Version" + e);
            bitoVersion = Constant.BITO_VERSION_STATIC;
        }
        // System.out.println("Bito version is = " + bitoVersion);
        return bitoVersion;
    }

   private static String fetchBitoVersion() {
        String plugInId = "";
    try {
        plugInId = PluginManagerCore.getPlugin(PluginId.findId("co.bito.bito-intellij")).getVersion();
    } catch(NoSuchMethodError noSuchMethodError) {
        System.out.println("Error getting Bito Version using new API, trying old API: " + noSuchMethodError);
        plugInId =  PluginManagerCore.getPlugin(PluginId.getId("co.bito.bito-intellij")).getVersion();
    }
    return plugInId;
   }

    public static String getOS() {
        try {
            return System.getProperty("os.name");
        } catch (Exception e) {
            System.out.println("Error getting System information" + e);
            return "NA";
        }
    }

    /**
     * Get the major or minor or all (including the major, minor, and build numbers)
     * string of the IDE
     */
    public static String getJetBrainsIDEVersion(String whichVersion) {
        ApplicationInfo applicationInfo = ApplicationInfo.getInstance();
        String version = "";
        try {

            if(whichVersion.equalsIgnoreCase("major")) {
                version = applicationInfo.getMajorVersion();
            } else {
                version = applicationInfo.getFullVersion();
            }
            System.out.pritn("Version ==" + version);
        } catch (Exception e) {
            System.out.println("Error getting IDE full version" + e);
        }
        return version;
    }

    public static String getIndexMgmntFilePath() {
        String bitoHomeDir = System.getProperty("user.home") + File.separator + ".bito";
        String lcaDir = bitoHomeDir + File.separator + "localcodesearch" + File.separator + "index";
        String indexMgmntFile = lcaDir + File.separator + "index_mgmt.json";

        return indexMgmntFile;

    }

    public static boolean isIndexMgmntFileExists() {
        boolean isExists = true;
        try {
            String filePath = getIndexMgmntFilePath();
            File file = new File(filePath);

            isExists = file.exists() && file.isFile();
        } catch (Exception ex) {
            System.out.println("error to find index mgmnt file: " + ex);
        }
        return isExists;
    }

    public static String getUTCTimeStampAsString() {
        Instant instant = Instant.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
                .withZone(ZoneOffset.UTC);

        String utcTimestamp = formatter.format(instant);
        return utcTimestamp;
    }

    public static int compareVersion(String version01, String version02) {

        String version1 = GenericUtils.extractVersion(version01);
        String version2 = GenericUtils.extractVersion(version02);

        String[] arr1 = version1.split("\\.");
        String[] arr2 = version2.split("\\.");

        int i = 0;
        // Compare each segment of the version numbers
        while (i < arr1.length || i < arr2.length) {
            // If one of the versions has more segments, consider the missing segments as 0
            int num1 = i < arr1.length ? Integer.parseInt(arr1[i]) : 0;
            int num2 = i < arr2.length ? Integer.parseInt(arr2[i]) : 0;

            if (num1 < num2) {
                return -1;
            } else if (num1 > num2) {
                return 1;
            }
            i++;
        }
        return 0;
    }

    public static String extractVersion(String input) {
        // Define a regular expression pattern to match version numbers (digits and dots)
        Pattern pattern = Pattern.compile("(\\d+\\.\\d+\\.\\d+)");

        // Create a Matcher object
        Matcher matcher = pattern.matcher(input);

        // Find the first occurrence of the pattern
        if (matcher.find()) {
            return matcher.group(1); // Extract and return the matched version
        } else {
            return input; // If no version is found, return the original string
        }
    }

    private static String deviceIdCache = null;

    public static String getDeviceId() {
        if(deviceIdCache == null) {
            return readOrCreateDeviceId();
        }
        return deviceIdCache;
    }


    public static String readDeviceId() {
        String dvcId = "";
        try {
            String filePath = getDeviceIdFilePath();
            File file = new File(filePath);
            boolean isExists = file.exists() && file.isFile();
            if(!isExists) {
                System.out.println("Device Id file does not exist");
                return dvcId;
            }
            byte[] fileBytes = Files.readAllBytes(Paths.get(filePath));
            String content = new String(fileBytes, StandardCharsets.UTF_8);
            ObjectMapper objectMapper = new ObjectMapper();
            DeviceId deviceId = objectMapper.readValue(content, DeviceId.class);
            if(deviceId != null && deviceId.getDeviceId() != null) {
                dvcId = deviceId.getDeviceId();
            } else {
                System.out.println("Device id null or file corrupted ");
            }
        } catch (Exception e) {
            System.out.println("exception caught or could not read device id " + e);
        }
        return dvcId;
    }

    @NotNull
    private static String getDeviceIdFilePath() {
        return System.getProperty("user.home") + File.separator + ".bito" + File.separator + "uid" + File.separator + "deviceId.json";
    }

    private static String calculateDeviceId() {
        String calculatedDvcId = "";
        try {

            String machineId = MachineIdUtils.getMachineId();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(machineId.getBytes(StandardCharsets.UTF_8));
            String[] hexadecimalFormat = new String[hash.length];
            for (int i = 0; i < hash.length; i++) {
            hexadecimalFormat[i] = String.format("%02x", hash[i]);
        }
        calculatedDvcId = String.join("", hexadecimalFormat);

        } catch(Exception ex) {
            System.out.println("Exception caught at calculate device Id " + ex);
        }
        return calculatedDvcId;

    }

    public static String readOrCreateDeviceId() {
        /**
         * sha 256 hex hash of string "DEVICE_ID_NA", which is used as internal default value
         * if there is any error in deviceId calculation
         */
        String defaultDvcId = "b9365fd2a13915c28bd9d5cf3892ad5ffd4a58439676ea765dfe888a0e8002ea";

        String deviceId = readDeviceId();

        if (deviceId != null && !deviceId.isEmpty()) {
            deviceIdCache = deviceId;
            return deviceId;
        }
        
        try {
            deviceId = calculateDeviceId();
            System.out.println(" calculated device id ::::   " + deviceId);
            if(deviceId.isEmpty()) {
                //default valie
                deviceId = defaultDvcId;
                deviceIdCache = deviceId;
            } else {
                 writeDeviceIdToFile(deviceId);
            }
            deviceIdCache = deviceId;
            
        } catch (Exception e) {
            System.out.println("exception caught at read or create device id " + e);
            deviceId = defaultDvcId;
        }
        return deviceId;
    }

    private static void writeDeviceIdToFile(String deviceId) {
        try {
            String filePath = getDeviceIdFilePath();
            DeviceId deviceIdObj = new DeviceId(deviceId);
            String content = new Gson().toJson(deviceIdObj);
            Files.write(Paths.get(filePath), content.getBytes(StandardCharsets.UTF_8));

        } catch(Exception ex) {
            System.out.println("exception caught to write device id to file :" + ex);
        }
    }


    public static NotificationType getNotificationType(String type)
    {
        NotificationType notificationType = NotificationType.INFORMATION;

        switch(type)
        {
            case "warning" : notificationType= NotificationType.WARNING;
                break;
            case "info" : notificationType= NotificationType.INFORMATION;
                break;
            case "error" : notificationType= NotificationType.ERROR;
                break;
        }
        return notificationType;
    }

    public static String createDirectoryAndFile(String relativePath) {
        String[] pathArray = relativePath.split("/");
        String joinPath = String.join(File.separator, pathArray);
        String completePath = System.getProperty("user.home") + File.separator + ".bito" + File.separator + joinPath;

        try{
            // Convert the file path string to a Path object
            Path path = FileSystems.getDefault().getPath(completePath);

            // Get the parent path (path before the last element)
            Path dirPath = path.getParent();

            if (dirPath != null) {
                File fileDir = new File(dirPath.toString());
                if (!fileDir.exists()) {
                    fileDir.mkdirs();
                }
            }
            Path fileName = path.getFileName();
            if (fileName != null) {
                File file = new File(completePath);
                if (!file.exists()) {
                    file.createNewFile();
                }
            }
        } catch (IOException ioe) {
            System.out.println("error in writing files ");
            ioe.printStackTrace();
            return "";
        } catch (Exception e) {
            System.out.println("error in writing files ");
            e.printStackTrace();
            return "";
        }
        return completePath;
    }
}
