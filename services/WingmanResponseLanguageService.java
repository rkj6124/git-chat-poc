package co.bito.intellij.wingman.services;

import static co.bito.intellij.wingman.Constant.HTTP_SUCCESS_RESPONSE;

import java.nio.file.Path;
import java.util.Map;

import org.cef.callback.CefQueryCallback;
import org.jetbrains.annotations.NotNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import co.bito.intellij.services.FileService;
import co.bito.intellij.wingman.util.WingmanUtil;

public class WingmanResponseLanguageService {

    private static final Logger LOG = Logger.getInstance(WingmanResponseLanguageService.class);

    public void updateResponseLanguage(@NotNull Project project, @NotNull Map<String, Object> data,
            @NotNull CefQueryCallback callback) {

        WingmanUtil util = new WingmanUtil();
        FileService fileService = new FileService();
        ObjectMapper mapper = new ObjectMapper();

        JsonObject jsonReturnObj = new JsonObject();
        try {
            if (data == null || data.isEmpty()) {
                callback.failure(500, "invalid request parameters");
                return;
            }

            String newLanguage = data.get("responseLanguage").toString();

            Path configFilePath = util.getWingmanConfigPath(project);

            String localJson = fileService.readFile(configFilePath.toString());

            // parse json into ObjectNode to update root level filed `responseLanguage`
            ObjectNode configNode = (ObjectNode) mapper.readTree(localJson);

            // update or insert
            configNode.put("responseLanguage", newLanguage);

            // pretty print the file contents
            String updatedContent = mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(configNode);

            fileService.writeFile(configFilePath.toString(), updatedContent);

            LOG.info("updated response language in config.json to: " + newLanguage);

            jsonReturnObj.addProperty("status", HTTP_SUCCESS_RESPONSE);
            jsonReturnObj.addProperty("message", "wingman response language set to " + newLanguage);

            callback.success(jsonReturnObj.toString());

        } catch (Exception e) {
            LOG.info("error updating response language in config.json", e);
            callback.failure(500, e.getMessage());
        }
    }

}
