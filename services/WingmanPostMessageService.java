package co.bito.intellij.wingman.services;

import co.bito.intellij.services.BitoWindowService;
import co.bito.intellij.utils.DeviceId;
import co.bito.intellij.utils.GenericUtils;
import co.bito.intellij.wingman.WingmanConstants;
import co.bito.intellij.wingman.WingmanEnvConfig;
import co.bito.intellij.wingman.modal.WingmanUserInfo;

import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.WindowManager;
import org.json.JSONObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;

import static co.bito.intellij.local_context.Constants.WEBSOCKET_URL;
import static co.bito.intellij.local_context.Constants.WINGMAN_STATUS_UPDATE;
import static co.bito.intellij.services.HttpService.getXClientInfo;
import static co.bito.intellij.wingman.WingmanEnvConfig.getWingmanEnvConfig;

/**
 * Service class for handling Wingman post message operations
 */
public class WingmanPostMessageService {

    private static final Logger logger = Logger.getLogger(WingmanPostMessageService.class.getName());

    /**
     * Notifies the Bito UI about Wingman status changes
     *
     * @param wingmanUserInfo User information
     */
    public static void notifyBitoUIForWingmanStatus(WingmanUserInfo wingmanUserInfo, JsonObject messageToSend, Project project) {
        try {
            BitoWindowService bitoWindowService = project.getService(BitoWindowService.class);
            if (bitoWindowService != null) {
                bitoWindowService.notifyIdeAppInstance(messageToSend);
            } else {
                logger.info("BitoWindowService is not available for the project: " + project.getName());
            }

            notifyBitoUIForWingmanStatusOnWebSocket(wingmanUserInfo, messageToSend.get("key").getAsString(), null);
        } catch (Exception e) {
            logger.info("Error notifying Bito UI for Wingman status: " + e.getMessage());
        }
    }

    public static void notifyBitoUIForWingmanStatusOnWebSocket(
            WingmanUserInfo wingmanUser,
            String event,
            String msg) throws Exception {

        // Create a JSON object as sendObject
        JSONObject sendObject = new JSONObject();

        // Populate the JSON object with the required fields
        sendObject.put("wsId", wingmanUser.getWsId());
        sendObject.put("userId", wingmanUser.getUserId());
        sendObject.put("token", wingmanUser.getToken());
        sendObject.put("msg", msg);
        sendObject.put("wgId", wingmanUser.getWgId());

        // Call the message sending function with the event and JSON object
        messageFromJBToWingmanPanelByEvent(event, sendObject);
    }

    /**
     * Helper function to send a message from JB to
     * the Wingman panel view based on the specified event.
     * <p>
     * The event key is used to identify the type of event.
     * Supported events:
     * [Download Start | Download In Progress | Download Finished]
     */
    public static void messageFromJBToWingmanPanelByEvent(
            String event,
            JSONObject message) {

        try {
            // Call the sendCallOverWebSocket function with the event and message
            sendCallOverWebSocket(event, message);
        } catch (Exception e) {
            // Log the error
            System.out.println("Error in messageFromJBToWingmanPanelByEvent: " + e);
        }
    }

    /**
     * Sends a call over WebSocket with the specified event and message.
     */
    public static void sendCallOverWebSocket(String event, JSONObject message) {
        try {
            // Get device ID
            String deviceId = GenericUtils.getDeviceId();
            if (deviceId == null || deviceId.isEmpty()) {
                //if device is null, fetch from deviceId.json file
                DeviceId deviceIdObj = new DeviceId();
                deviceId = deviceIdObj.readDeviceIdFromFile();
            }

            // Extract user ID from message
            String userId = message.getString("userId");

            // Create message JSON
            JSONObject msgObj = new JSONObject();
            msgObj.put("event", event);
            msgObj.put("message", message);
            msgObj.put("deviceId", deviceId);
            String msg = msgObj.toString();

            // Create payload
            JSONObject payload = new JSONObject();
            payload.put("chid", 0);
            payload.put("msg", msg);
            payload.put("sender", Integer.parseInt(userId));
            payload.put("mtyId", WINGMAN_STATUS_UPDATE);

            // Get authorization header
            Project project = Objects.requireNonNull(WindowManager.getInstance().getIdeFrame(null).getProject());
            String xClientInfo = getXClientInfo(project);

            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", "Bito-intellij-extension");
            headers.put("X-ClientInfo", xClientInfo);
            headers.put("authorization", message.getString("token"));
            headers.put("cid", message.get("wgId").toString());
            headers.put("ctype", "2");
            headers.put("wid", message.get("wsId").toString());


            // Make the web socket API call
            WingmanEnvConfig wingmanEnvConfig = getWingmanEnvConfig();
            String host = wingmanEnvConfig.getWebsocketServiceHost();
            String url = host + WEBSOCKET_URL;

            // Make HTTP POST request using Java 11 HttpClient
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json");

            // Add all headers from headerConfig
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                requestBuilder.header(entry.getKey(), entry.getValue());
            }

            HttpRequest request = requestBuilder
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                    .build();

            client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(HttpResponse::body)
                    .thenAccept(response -> {
                        logger.info("Response received from web socket call: " + response);
                    })
                    .exceptionally(err -> {
                        System.out.println("Error: " + err);
                        return null;
                    });

        } catch (Exception error) {
            logger.info("Error making web socket API call from IDE backend: " + error);
        }
    }

}
