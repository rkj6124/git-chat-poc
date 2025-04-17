package co.bito.intellij.wingman.services;

import android.util.Log;
import co.bito.intellij.db.util.DBConstants;
import co.bito.intellij.wingman.JetBrainsIDEInfo;
import co.bito.intellij.wingman.WingmanManager;
import co.bito.intellij.wingman.config.WingmanConfigService;
import co.bito.intellij.wingman.modal.BitoWingmanServer;
import co.bito.intellij.wingman.modal.WingmanContext;
import co.bito.intellij.wingman.model.WingmanConfigParam;
import co.bito.intellij.wingman.process.ProcessException;
import co.bito.intellij.wingman.process.ProcessHandler;
import co.bito.intellij.wingman.process.WingmanProcess;
import co.bito.intellij.wingman.process.dao.WingmanProcessDao;
import co.bito.intellij.wingman.process.dao.WorkspaceUserProjectDao;
import co.bito.intellij.wingman.process.dao.impl.DBManager;
import co.bito.intellij.wingman.process.dao.impl.WingmanProcessDaoImpl;
import co.bito.intellij.wingman.process.dao.impl.WorkspaceUserProjectDaoImpl;
import co.bito.intellij.wingman.process.model.WingmanProcessEntity;
import co.bito.intellij.wingman.process.model.WorkspaceUserProject;
import co.bito.intellij.wingman.util.WingmanUtil;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;

import java.sql.Connection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Service for managing Wingman processes.
 * This service provides a high-level interface for starting, stopping, and monitoring
 * Wingman processes, with thread-safe operations and proper error handling.
 */
@Service
public final class WingmanNewProcessService implements Disposable, ProcessHandler {
    private static final Logger LOG = Logger.getInstance(WingmanNewProcessService.class);
    private static final int DEFAULT_PORT = 3500;
    private static WingmanNewProcessService instance;
    private final ProcessHandler processHandler;
    private final WingmanProcessDao processDao;
    private final WorkspaceUserProjectDao workspaceUserProjectDao;
    private final String dbUrl;
    private final Map<String, ReentrantLock> projectLocks = new ConcurrentHashMap<>();

    private final Map<String, WingmanProcessEntity> processCache = new ConcurrentHashMap<>();
    private final DBManager dbmanager;

    private WingmanNewProcessService() {
        // dbUrl = "jdbc:sqlite:/Users/shashikantthorat/.bito/db/bito";
        Path processFoldePath = Paths.get(DBConstants.SQL_LITE_DB_URL + "-process");
        if (!processFoldePath.toFile().exists()) {
            processFoldePath.toFile().mkdirs();
        }

        dbUrl = "jdbc:sqlite:" + DBConstants.SQL_LITE_DB_URL + "-process" + "/wingman_process.db";

        dbmanager=DBManager.getInstance(dbUrl);
        this.processHandler = new WingmanProcess();

        this.workspaceUserProjectDao = WorkspaceUserProjectDaoImpl.getInstance();
        this.processDao = WingmanProcessDaoImpl.getInstance();

        // LOG.info("Initialized WingmanProcessService for project: " + project.getName());
    }

    /**
     * Starts a Wingman process for the specified workspace user.
     * If a process already exists for this user, returns the existing process information.
     * Otherwise, allocates an available port and starts a new process.
     *
     * @param workspaceUserId the workspace user ID
     * @param projectId       the project ID (optional)
     * @return a map containing process information
     * @throws ProcessException if the process cannot be started
     */
    public Map<String, Object> startProcess(String workspaceUserId, String projectId, Project project) throws ProcessException {
        LOG.info("Start Request for workspace user ID: " + workspaceUserId + " ProjectId:" + projectId);
        //workspaceUserId="1321-1277";//TODO: Need to remove it for testing
        if (workspaceUserId == null || workspaceUserId.isEmpty()) {
            throw new ProcessException("Workspace user ID is required");
        }
        ReentrantLock lock = projectLocks.get(workspaceUserId);
        try {

            if (lock == null) {
                lock = new ReentrantLock();
                projectLocks.put(workspaceUserId, lock);
            }
            lock.lock();
            Connection conn = dbmanager.getConnection();
            conn.setAutoCommit(false);
            processDao.lock(conn, workspaceUserId);

            try {

                Optional<WingmanProcessEntity> existingProcess = findProcessByWorkspaceUserId(conn, workspaceUserId);
                int port = 0;
                if (existingProcess.isPresent()) {
                    WingmanProcessEntity entity = existingProcess.get();
                    port = entity.getPort();
                    // Check if process is actually running
                    try {
                        // Map<String, Object> healthInfo = processHandler.healthCheck(workspaceUserId);
                        //if (Boolean.TRUE.equals(healthInfo.get("running"))) {
                        if (port != 0 && !processHandler.checkPortAvailability("localhost", entity.getPort())) {
                            LOG.info("Process already running for workspace user ID: " + workspaceUserId);
                            if (entity.getStatus() != STATUS_RUNNING) {
                                saveProcess(conn, entity);
                            }
                            if (!entity.getProjectId().equals(projectId)) {
                                workspaceUserProjectDao.save(conn, new
                                        WorkspaceUserProject(workspaceUserId, projectId,
                                        entity.getProcessId(), (int) ProcessHandle.current().pid()));
                            }

                            LOG.info("Process already running for workspace user ID for parent id : " + (int) ProcessHandle.current().pid());

                            processDao.unlock(conn, workspaceUserId);
                            WingmanConfigParam params = new WingmanConfigParam();
                            params.setPort(entity.getPort());
                            params.setHost(entity.getHost());
                            params.setExecutableWingmanBinaryPath("");
                            conn.commit();
                            updateServerInfo(params, project);
                            return createInfoMap(entity);
                        }
                    } catch (Exception e) {

                        // Process not found in memory, continue to start a new one
                        LOG.info("Process not found in memory for workspace user ID: " + workspaceUserId + ", starting new one");
                    }
                }
                //Need to get singlton by configure with plugin
                WingmanConfigService confService = new WingmanConfigService();
                confService.createEnvConfig(project);

                if (port != 0 && !processHandler.checkPortAvailability("localhost", port)) {
                    LOG.info("Process not found in memory for workspace user ID: " + workspaceUserId + ", starting new one");
                    port = 0;

                }

                if (port == 0) {
                    port = processDao.findHighestPort(conn);
                    while (true) {
                        port = port + 1;
                        if (processHandler.checkPortAvailability("localhost", port)) {
                            LOG.info("Find port " + port + ", starting new server for " + workspaceUserId);
                            break;
                        }

                    }
                }
                WingmanConfigParam param = confService.createWingmanConfig(project, port, "localhost");
                LOG.info("Process-----:" + ProcessHandle.current().pid());
                // Start a new process
                Map<String, Object> processInfo = processHandler.start(workspaceUserId, param);

                // Save process information to database
                WingmanProcessEntity entity = createEntityFromInfo(processInfo);
                entity.setProjectId(projectId);
                saveProcess(conn, entity);
                // String ideInstanceId, String workspaceUserId, String projectId, int processId, int parentProcessId
                if (entity.getStatus().equals("RUNNING")) {
                    workspaceUserProjectDao.save(conn, new
                            WorkspaceUserProject(workspaceUserId, projectId,
                            entity.getProcessId(), (int) ProcessHandle.current().pid()));
                }
                processDao.unlock(conn, workspaceUserId);
                conn.commit();
                updateServerInfo(param, project);
                return processInfo;
            } catch (Exception e) {

                conn.rollback();
                throw new ProcessException("Database error while starting process", e);
            } finally {

            }

        } catch (SQLException e) {
            // conn.rollback();

            throw new ProcessException("Database error while starting process", e);
        } finally {
            LOG.info("Releasing lock for start process.");
            if (lock != null)
                lock.unlock();
        }
    }


    /**
     * Stops a Wingman process for the specified workspace user.
     *
     * @param parentProcessId the workspace user ID
     * @return true if the process was successfully stopped, false otherwise
     * @throws ProcessException if an error occurs while stopping the process
     */
    public boolean stopProcess(int parentProcessId,String projectId) throws ProcessException {
        if (parentProcessId <= 0) {
            throw new ProcessException("Process ID is required");
        }

        try {
            Connection conn = dbmanager.getConnection();

            // Check if process exists in database
            Optional<WorkspaceUserProject> existingWorkspaneProject = workspaceUserProjectDao.findByParentProcessId(conn, parentProcessId,projectId);

            if (!existingWorkspaneProject.isPresent()) {
                LOG.warn("No process found in database for parentProcess  ID: " + parentProcessId);
                return false;
            }
            WorkspaceUserProject workspaceProjectEntity = existingWorkspaneProject.get();
            workspaceUserProjectDao.deleteByParentProcessId(conn, parentProcessId,projectId);
            int runningCount = workspaceUserProjectDao.getProcessCountForWorkspaceUserId(conn, workspaceProjectEntity.getWorkspaceUserId());
            if (runningCount > 0) {
                //Not  stop process if mapping count references of other project
                LOG.warn("mapped Running process cont: " + runningCount);
                return false;
            }
            // Check if process exists in database
            Optional<WingmanProcessEntity> existingProcess = processDao.findByWorkspaceUserId(conn, workspaceProjectEntity.getWorkspaceUserId());

            if (!existingProcess.isPresent()) {
                LOG.warn("No process found in database for process ID: " + parentProcessId);
                return false;
            }
            WingmanProcessEntity entity = existingProcess.get();

            // Stop the process
            boolean success = processHandler.stop(entity.getProcessId(),entity.getProjectId());
            if (!success) {
                throw new ProcessException("Failed to stop process with ID: " + entity.getProcessId());
            }
            // Remove process from database
            processDao.deleteByWorkspaceUserId(conn, entity.getWorkspaceUserId());
            processCache.remove(entity.getWorkspaceUserId());

            return success;

        } catch (SQLException e) {
            throw new ProcessException("Database error while stopping process", e);
        }

    }



    /**
     * Finds a process by workspace user ID.
     *
     * @param workspaceUserId the workspace user ID
     * @return an Optional containing the process entity if found, or empty if not found
     * @throws SQLException if a database error occurs
     */
    private Optional<WingmanProcessEntity> findProcessByWorkspaceUserId(Connection conn, String workspaceUserId) throws SQLException {
        // Check cache first
        if (processCache.containsKey(workspaceUserId)) {
            return Optional.of(processCache.get(workspaceUserId));
        }

        // Check database
        Optional<WingmanProcessEntity> entity = processDao.findByWorkspaceUserId(conn, workspaceUserId);

        // Update cache if found
        entity.ifPresent(e -> processCache.put(workspaceUserId, e));

        return entity;
    }




    /**
     * Saves a process entity to the database and cache.
     *
     * @param entity the entity to save
     * @throws SQLException if a database error occurs
     */
    private void saveProcess(Connection conn, WingmanProcessEntity entity) throws SQLException {
        processDao.save(conn, entity);
        processCache.put(entity.getWorkspaceUserId(), entity);
    }

    /**
     * Creates a process entity from process information.
     *
     * @param info the process information
     * @return a WingmanProcessEntity
     */
    private WingmanProcessEntity createEntityFromInfo(Map<String, Object> info) {
        return WingmanProcessEntity.builder()
                .workspaceUserId((String) info.get("workspaceUserId"))
                .host((String) info.get("host"))
                .port((Integer) info.get("port"))
                .processId((Integer) info.get("processId"))
                .platform((String) info.get("platform"))
                .projectId((String) info.get("projectId"))
                .status((String) info.get("status"))
                .build();
    }

    /**
     * Creates an info map from a WingmanProcessEntity.
     *
     * @param entity the WingmanProcessEntity
     * @return a map containing process information
     */
    private Map<String, Object> createInfoMap(WingmanProcessEntity entity) {
        Map<String, Object> infoMap = new HashMap<>();
        infoMap.put("workspaceUserId", entity.getWorkspaceUserId());
        infoMap.put("host", entity.getHost());
        infoMap.put("port", entity.getPort());
        infoMap.put("processId", entity.getProcessId());
        infoMap.put("platform", entity.getPlatform());
        infoMap.put("projectId", entity.getProjectId());
        infoMap.put("status", entity.getStatus());
        return infoMap;
    }

    /**
     * Gets the local host name.
     *
     * @return the local host name, or "localhost" if it cannot be determined
     */
    private String getLocalHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            LOG.warn("Could not determine local host name", e);
            return "localhost";
        }
    }

    @Override
    public void dispose() {
        // Stop all processes when service is disposed
       /* try {
            for (WingmanProcessEntity entity : processCache.values()) {
                try {
                    processHandler.stop(entity.getWorkspaceUserId());
                } catch (ProcessException e) {
                    LOG.warn("Failed to stop process for workspace user ID: " + entity.getWorkspaceUserId(), e);
                }
            }
        } finally {
            processCache.clear();
        }*/
    }

    /**
     * Gets a singleton instance of WingmanProcessService for the specified project.
     *
     * @param project the project
     * @return a WingmanProcessService instance
     */
    public static WingmanNewProcessService getInstance(Project project) {
        return project.getService(WingmanNewProcessService.class);
    }

    /**
     * Starts a process for the specified workspace user.
     * If a process already exists for this user, returns the existing process information.
     * Otherwise, allocates an available port and starts a new process.
     *
     * @param workspaceUserId The unique identifier for the workspace user
     * @param configParam     The WingmanConfigParam identifier (optional)
     * @return Map containing process information (host, port, processId, etc.)
     * @throws ProcessException if the process cannot be started
     */
    @Override
    public Map<String, Object> start(String workspaceUserId, WingmanConfigParam configParam) throws ProcessException {
        return Map.of();
    }

    /**
     * Stops a running process for the specified workspace user.
     *
     * @param processId The processId
     * @return true if the process was successfully stopped, false otherwise
     * @throws ProcessException if an error occurs while stopping the process
     */
    @Override
    public boolean stop(int processId,String projectId) throws ProcessException {
        return stopProcess(processId,projectId);
    }

    /**
     * Checks the health of a process for the specified workspace user.
     *
     * @param workspaceUserId The unique identifier for the workspace user
     * @return Map containing health status information
     * @throws ProcessException if the health check fails
     */
    @Override
    public Map<String, Object> healthCheck(String workspaceUserId) throws ProcessException {
        return Map.of();
    }



    public boolean checkPortAvailability(String host, int port) {
        return processHandler.checkPortAvailability(host, port);
    }


    private static synchronized WingmanNewProcessService getInstance() {
        if (instance == null) {
            instance = new WingmanNewProcessService();
        }
        return instance;
    }

    public static synchronized boolean startWingmanService(Project project) {
        try {
            LOG.info("calling start wingman service...");
            WingmanUtil wingmanUtil = new WingmanUtil();
            instance = getInstance();
            WingmanNewProcessService processService = WingmanNewProcessService.getInstance(project);
            String workspaceUserId = wingmanUtil.getUniqueId(project);
            JetBrainsIDEInfo jetBrainsIDEInfo = WingmanContext.getIDEInfo(project);
            String newProjectId = jetBrainsIDEInfo.getProjectName() + "-" + jetBrainsIDEInfo.getJbIdeVersion();
            instance.startProcess(workspaceUserId, newProjectId, project);

            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }


    public static void startWingmanServiceAsync(Project project, Consumer<Boolean> callback) {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            callback.accept(startWingmanService(project));
        });
    }

    private void updateServerInfo(WingmanConfigParam params, Project project) {
        // BitoWingmanServer savedServerInfo = WingmanContext.getServerInfo(project);
        LOG.info("update for server info");
        BitoWingmanServer wingmanServer = WingmanContext.getServerInfo(project);
        if (wingmanServer == null) {
            wingmanServer = new BitoWingmanServer();
        }
        wingmanServer.setPort(params.getPort());
        wingmanServer.setHost("localhost");
        WingmanContext.setServerInfo(project, wingmanServer);
        LOG.info("Calling start processing monitoring");
        new WingmanManager().startProcessMonitoring(params.getExecutableWingmanBinaryPath(), project);
    }

    public static boolean removePrcoess(String projectId) {
        instance = getInstance();
        try {
            int processId = (int) ProcessHandle.current().pid();
            //instance.workspaceUserProjectDao.deleteByParentProcessId(instance.dbmanager.getConnection(), processId);
            instance.stop(processId, projectId);
        } catch (Exception e) {
            LOG.error("Error while remove process",e);
            throw new RuntimeException(e);
        }
        return true;
    }


}

