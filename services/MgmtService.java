package co.bito.intellij.wingman.services;

/***************************************************************************
 * Copyright (C) 2021, Bito Inc - All Rights Reserved
 * Unauthorized copying of this file, its content or modification
 * via any medium is strictly prohibited.
 * Proprietary and confidential
 *
 ***************************************************************************/

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.logging.Logger;

import co.bito.intellij.wingman.WingmanEnvConfig;
import org.json.JSONObject;

import static co.bito.intellij.wingman.WingmanEnvConfig.getWingmanEnvConfig;

/**
 * Utility class to handle Bito API key and access token operations
 */
public class MgmtService {

    private static final Logger logger = Logger.getLogger(MgmtService.class.getName());

    /**
     * Gets the base URL for Bito API calls
     *
     * @return The base URL string
     */
    public static String getBaseUrl() {
        WingmanEnvConfig wingmanEnvConfig = getWingmanEnvConfig();
        return wingmanEnvConfig.getMgmntApiUrl();
    }

    /**
     * Retrieves or creates a Bito access token for the given workspace and user
     *
     * @param wsId   The workspace ID
     * @param userId The user ID
     * @param token  The authorization token
     * @param key    The workspace key name
     * @return The workspace key, or null if an error occurs
     */
    public static String getBitoAccessToken(Integer wsId, Integer userId, String token, String key) {
        String workspaceKey = "";
        try {
            String host = getBaseUrl();

            String url = host + "api/getOrCreateWSKey?userId=" + userId + "&workspaceId=" + wsId + "&wsKeyName=" + key;

            URL apiUrl = new URL(url);
            HttpURLConnection connection = (HttpURLConnection) apiUrl.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Authorization", token);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true);

            // Send an empty body to avoid 411 error
            try (OutputStream os = connection.getOutputStream()) {
                byte[] input = "{}".getBytes("utf-8"); // Sending an empty JSON object
                os.write(input, 0, input.length);
            }

            int responseCode = connection.getResponseCode();

            if (responseCode != 200 && responseCode != 202) {
                logger.info("Error occurred while getting bito access token for userId " + userId + " with " + responseCode);
                return workspaceKey;
            }

            // Read the response
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }

                // Parse JSON response
                JSONObject jsonResponse = new JSONObject(response.toString());
                workspaceKey = jsonResponse.getString("workspaceKey");
            }

            logger.info("received bito access key for " + wsId);

        } catch (Exception error) {
            logger.info("error occurred while getting bito access token: " + error.getMessage());
        }

        return workspaceKey;
    }
}
