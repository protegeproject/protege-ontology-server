package org.protege.editor.owl.server.base;

import edu.stanford.protege.metaproject.Manager;
import edu.stanford.protege.metaproject.api.*;
import edu.stanford.protege.metaproject.api.exception.*;
import org.apache.commons.io.FileUtils;
import org.protege.editor.owl.server.api.CommitBundle;
import org.protege.editor.owl.server.api.ServerLayer;
import org.protege.editor.owl.server.api.exception.AuthorizationException;
import org.protege.editor.owl.server.api.exception.ServerServiceException;
import org.protege.editor.owl.server.http.HTTPServer;
import org.protege.editor.owl.server.versioning.ChangeHistoryImpl;
import org.protege.editor.owl.server.versioning.Commit;
import org.protege.editor.owl.server.versioning.InvalidHistoryFileException;
import org.protege.editor.owl.server.versioning.api.ChangeHistory;
import org.protege.editor.owl.server.versioning.api.DocumentRevision;
import org.protege.editor.owl.server.versioning.api.HistoryFile;
import org.protege.editor.owl.server.versioning.api.ServerDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.*;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * The main server that acts as the end-point server where user requests to the
 * server get implemented.
 *
 * @author Josef Hardi <johardi@stanford.edu> <br>
 *         Stanford Center for Biomedical Informatics Research
 */
public class ProtegeServer extends ServerLayer {

    private Logger logger = LoggerFactory.getLogger(ProtegeServer.class);

    private ServerConfiguration configuration;

    private final ConfigurationManager manager;

    private File configurationFile;

    private static final PolicyFactory factory = Manager.getFactory();

    public ProtegeServer(ServerConfiguration configuration) {
        this.configuration = checkNotNull(configuration);
        this.manager = configuration.getConfigurationManager();

        String configLocation = System.getProperty(HTTPServer.SERVER_CONFIGURATION_PROPERTY);
        configurationFile = new File(configLocation);
    }

    @Override
    public ServerConfiguration getConfiguration() {
        return configuration;
    }

    @Override
    public void createUser(AuthToken token, User newUser, Optional<? extends Password> newPassword)
            throws AuthorizationException, ServerServiceException {
        synchronized (configuration) {
            try {
                logger.info(printLog(token.getUser(), "Add user", newUser.toString()));
                manager.addUser(newUser);
                saveChanges();
            }
            catch (IdAlreadyInUseException e) {
                logger.error(printLog(token.getUser(), "Add user", e.getMessage()));
                throw new ServerServiceException(e.getMessage(), e);
            }
        }
        if (newPassword.isPresent()) {
            synchronized (configuration) {
                try {
                    Password password = newPassword.get();
                    if (password instanceof SaltedPasswordDigest) {
                        manager.registerUser(newUser.getId(), (SaltedPasswordDigest) password);
                    }
                }
                catch (IdAlreadyInUseException e) {
                    logger.error(printLog(token.getUser(), "Add password", e.getMessage()));
                    throw new ServerServiceException(e.getMessage(), e);
                }
                saveChanges();
            }
        }
    }

    @Override
    public void deleteUser(AuthToken token, UserId userId) throws AuthorizationException, ServerServiceException {
        synchronized (configuration) {
            try {
                User user = configuration.getUser(userId);
                logger.info(printLog(token.getUser(), "Remove user", user.toString()));
                manager.removeUser(user);
                saveChanges();
            }
            catch (UnknownUserIdException e) {
                logger.error(printLog(token.getUser(), "Remove user", e.getMessage()));
                throw new ServerServiceException(e.getMessage(), e);
            }
        }
    }

    @Override
    public void updateUser(AuthToken token, UserId userId, User updatedUser, Optional<? extends Password> updatedPassword)
            throws AuthorizationException, ServerServiceException {
        synchronized (configuration) {
            try {
                logger.info(printLog(token.getUser(), "Modify user", updatedUser.toString()));
                manager.setUser(userId, updatedUser);
                if (updatedPassword.isPresent()) {
                    Password password = updatedPassword.get();
                    if (password instanceof SaltedPasswordDigest) {
                        manager.changePassword(userId, (SaltedPasswordDigest) password);
                    }
                }
                saveChanges();
            }
            catch (UnknownUserIdException e) {
                logger.error(printLog(token.getUser(), "Modify user", e.getMessage()));
                throw new ServerServiceException(e.getMessage(), e);
            }
            catch (UserNotRegisteredException e) {
                logger.error(printLog(token.getUser(), "Modify user", e.getMessage()));
                throw new ServerServiceException(e.getMessage(), e);
            }
        }
    }

    @Override
    public ServerDocument createProject(AuthToken token, ProjectId projectId, Name projectName, Description description,
            UserId owner, Optional<ProjectOptions> options) throws AuthorizationException, ServerServiceException {
        ServerDocument serverDocument = null;
        try {
            HistoryFile historyFile = createHistoryFile(projectId.get(), projectName.get());
            synchronized (configuration) {
                Project newProject = factory.getProject(projectId, projectName, description, historyFile, owner, options);
                logger.info(printLog(token.getUser(), "Add project", newProject.toString()));
                try {
                    manager.addProject(newProject);
                    saveChanges();
                }
                catch (IdAlreadyInUseException e) {
                    logger.error(printLog(token.getUser(), "Add project", e.getMessage()));
                    throw new ServerServiceException(e.getMessage(), e);
                }
            }
            serverDocument = createServerDocument(historyFile);
        }
        catch (IOException e) {
            String message = "Failed to create history file in remote server";
            logger.error(printLog(token.getUser(), "Add project", message));
            throw new ServerServiceException(message, e);
        }
        return serverDocument;
    }

    private HistoryFile createHistoryFile(String projectDir, String filename) throws IOException {
        String rootDir = configuration.getServerRoot() + File.separator + projectDir;
        filename = filename.replaceAll("\\s+","_"); // to snake-case
        return HistoryFile.createNew(rootDir, filename);
    }

    private ServerDocument createServerDocument(HistoryFile historyFile) {
        final URI serverAddress = configuration.getHost().getUri();
        final Optional<Port> registryPort = configuration.getHost().getSecondaryPort();
        if (registryPort.isPresent()) {
            Port port = registryPort.get();
            return new ServerDocument(serverAddress, port.get(), historyFile);
        }
        else {
            return new ServerDocument(serverAddress, historyFile);
        }
    }

    @Override
    public void deleteProject(AuthToken token, ProjectId projectId, boolean includeFile)
            throws AuthorizationException, ServerServiceException {
        synchronized (configuration) {
            try {
                Project project = configuration.getProject(projectId);
                logger.info(printLog(token.getUser(), "Remove project", project.toString()));
                manager.removeProject(project);
                if (includeFile) {
                    String projectFilePath = project.getFile().getAbsolutePath();
                    HistoryFile historyFile = HistoryFile.openExisting(projectFilePath);
                    File projectDir = historyFile.getParentFile();
                    FileUtils.deleteDirectory(projectDir);
                }
                saveChanges();
            }
            catch (UnknownProjectIdException e) {
                logger.error(printLog(token.getUser(), "Remove project", e.getMessage()));
                throw new ServerServiceException(e.getMessage(), e);
            }
            catch (InvalidHistoryFileException e) {
                logger.error(printLog(token.getUser(), "Remove project", e.getMessage()));
                throw new ServerServiceException(e.getMessage(), e);
            }
            catch (IOException e) {
                logger.error(printLog(token.getUser(), "Remove project", e.getMessage()));
                throw new ServerServiceException(e.getMessage(), e);
            }
        }
    
    }
    
    

    @Override
    public void updateProject(AuthToken token, ProjectId projectId, Project updatedProject)
            throws AuthorizationException, ServerServiceException {
        synchronized (configuration) {
            try {
                logger.info(printLog(token.getUser(), "Modify project", updatedProject.toString()));
                manager.setProject(projectId, updatedProject);
                saveChanges();
            }
            catch (UnknownProjectIdException e) {
                logger.error(printLog(token.getUser(), "Modify project", e.getMessage()));
                throw new ServerServiceException(e.getMessage(), e);
            }
        }
    }

    @Override
    public ServerDocument openProject(AuthToken token, ProjectId projectId)
            throws AuthorizationException, ServerServiceException {
        try {
            Project project = configuration.getProject(projectId);
            logger.info(printLog(token.getUser(), "Open project", project.toString()));
            final URI serverAddress = configuration.getHost().getUri();
            final Optional<Port> registryPort = configuration.getHost().getSecondaryPort();
            final String path = project.getFile().getAbsolutePath();
            if (registryPort.isPresent()) {
                Port port = registryPort.get();
                return new ServerDocument(serverAddress, port.get(), HistoryFile.openExisting(path));
            }
            else {
                return new ServerDocument(serverAddress, HistoryFile.openExisting(path));
            }
        }
        catch (UnknownProjectIdException e) {
            logger.error(printLog(token.getUser(), "Open project", e.getMessage()));
            throw new ServerServiceException(e);
        }
        catch (InvalidHistoryFileException e) {
            String message = "Unable to access history file in remote server";
            logger.error(printLog(token.getUser(), "Open project", message), e);
            throw new ServerServiceException(message, e);
        }
    }

    @Override
    public void createRole(AuthToken token, Role newRole) throws AuthorizationException, ServerServiceException {
        synchronized (configuration) {
            try {
                logger.info(printLog(token.getUser(), "Add role", newRole.toString()));
                manager.addRole(newRole);
                saveChanges();
            }
            catch (IdAlreadyInUseException e) {
                logger.error(printLog(token.getUser(), "Add role", e.getMessage()));
                throw new ServerServiceException(e.getMessage(), e);
            }
        }

    }


    @Override
    public void deleteRole(AuthToken token, RoleId roleId) throws AuthorizationException, ServerServiceException {
        synchronized (configuration) {
            try {
                Role role = configuration.getRole(roleId);
                logger.info(printLog(token.getUser(), "Remove role", role.toString()));
                manager.removeRole(role);
                saveChanges();
            }
            catch (UnknownRoleIdException e) {
                logger.error(printLog(token.getUser(), "Remove role", e.getMessage()));
                throw new ServerServiceException(e.getMessage(), e);
            }
        }
    }

    @Override
    public void updateRole(AuthToken token, RoleId roleId, Role updatedRole)
            throws AuthorizationException, ServerServiceException {
        synchronized (configuration) {
            try {
                logger.info(printLog(token.getUser(), "Modify role", updatedRole.toString()));
                manager.setRole(roleId, updatedRole);
                saveChanges();
            }
            catch (UnknownRoleIdException e) {
                logger.error(printLog(token.getUser(), "Modify role", e.getMessage()));
                throw new ServerServiceException(e.getMessage(), e);
            }
        }
    }

    @Override
    public void createOperation(AuthToken token, Operation newOperation)
            throws AuthorizationException, ServerServiceException {
        synchronized (configuration) {
            try {
                logger.info(printLog(token.getUser(), "Add operation", newOperation.toString()));
                manager.addOperation(newOperation);
                saveChanges();
            }
            catch (IdAlreadyInUseException e) {
                logger.error(printLog(token.getUser(), "Add operation", e.getMessage()));
                throw new ServerServiceException(e.getMessage(), e);
            }
        }
    }

    @Override
    public void deleteOperation(AuthToken token, OperationId operationId)
            throws AuthorizationException, ServerServiceException {
        synchronized (configuration) {
            try {
                Operation operation = configuration.getOperation(operationId);
                logger.info(printLog(token.getUser(), "Remove operation", operation.toString()));
                manager.removeOperation(operation);
                saveChanges();
            }
            catch (UnknownOperationIdException e) {
                logger.error(printLog(token.getUser(), "Remove operation", e.getMessage()));
                throw new ServerServiceException(e.getMessage(), e);
            }
        }
    }

    @Override
    public void updateOperation(AuthToken token, OperationId operationId, Operation updatedOperation)
            throws AuthorizationException, ServerServiceException {
        synchronized (configuration) {
            try {
                logger.info(printLog(token.getUser(), "Modify operation", updatedOperation.toString()));
                manager.setOperation(operationId, updatedOperation);
                saveChanges();
            }
            catch (UnknownOperationIdException e) {
                logger.error(printLog(token.getUser(), "Modify operation", e.getMessage()));
                throw new ServerServiceException(e.getMessage(), e);
            }
        }
    }

    @Override
    public void assignRole(AuthToken token, UserId userId, ProjectId projectId, RoleId roleId)
            throws AuthorizationException, ServerServiceException {
        synchronized (configuration) {
            manager.addPolicy(roleId, projectId, userId);
            saveChanges();
        }
    }

    @Override
    public void retractRole(AuthToken token, UserId userId, ProjectId projectId, RoleId roleId)
            throws AuthorizationException, ServerServiceException {
        synchronized (configuration) {
            manager.addPolicy(userId, projectId, roleId);
            saveChanges();
        }
    }

    @Override
    public Host getHost(AuthToken token) throws AuthorizationException, ServerServiceException {
        return configuration.getHost();
    }

    @Override
    public void setHostAddress(AuthToken token, URI hostAddress) throws AuthorizationException, ServerServiceException {
        synchronized (configuration) {
            Optional<Port> secondaryPort = getHost(token).getSecondaryPort();
            Host updatedHost = factory.getHost(hostAddress, secondaryPort);
            manager.setHost(updatedHost);
            saveChanges();
        }
    }

    @Override
    public void setSecondaryPort(AuthToken token, int portNumber)
            throws AuthorizationException, ServerServiceException {
        synchronized (configuration) {
            URI hostAddress = getHost(token).getUri();
            Optional<Port> secondaryPort = Optional.empty();
            if (portNumber > 0) {
                secondaryPort = Optional.of(factory.getPort(portNumber));
            }
            Host updatedHost = factory.getHost(hostAddress, secondaryPort);
            manager.setHost(updatedHost);
            saveChanges();
        }
    }

    @Override
    public String getRootDirectory(AuthToken token) throws AuthorizationException, ServerServiceException {
        return configuration.getServerRoot().toString();
    }

    @Override
    public void setRootDirectory(AuthToken token, String rootDirectory)
            throws AuthorizationException, ServerServiceException {
        synchronized (configuration) {
            manager.setServerRoot(new File(rootDirectory));
            saveChanges();
        }
    }

    @Override
    public Map<String, String> getServerProperties(AuthToken token)
            throws AuthorizationException, ServerServiceException {
        return configuration.getProperties();
    }

    @Override
    public void setServerProperty(AuthToken token, String property, String value)
            throws AuthorizationException, ServerServiceException {
        synchronized (configuration) {
            manager.addProperty(property, value);
            saveChanges();
        }
    }

    @Override
    public void unsetServerProperty(AuthToken token, String property)
            throws AuthorizationException, ServerServiceException {
        synchronized (configuration) {
            manager.removeProperty(property);
            saveChanges();
        }
    }

    @Override
    public ChangeHistory commit(AuthToken token, ProjectId projectId, CommitBundle commitBundle)
            throws AuthorizationException, ServerServiceException {
        DocumentRevision baseRevision = commitBundle.getBaseRevision();
        ChangeHistory changeHistory = ChangeHistoryImpl.createEmptyChangeHistory(baseRevision);
        for (Commit commit : commitBundle.getCommits()) {
            changeHistory.addRevision(commit.getMetadata(), commit.getChanges());
            String message = String.format("Receive revision %s: %s",
                    changeHistory.getHeadRevision(), commit.getMetadata().getComment());
            logger.info(printLog(token.getUser(), "Commit changes", message));
        }
        return changeHistory;
    }

    
    @Override
    public List<User> getAllUsers(AuthToken token) throws AuthorizationException, ServerServiceException {
        return new ArrayList<>(configuration.getUsers());
    }

    @Override
    public List<Project> getProjects(AuthToken token, UserId userId)
            throws AuthorizationException, ServerServiceException {
        return new ArrayList<>(configuration.getProjects(userId));
    }

    @Override
    public List<Project> getAllProjects(AuthToken token) throws AuthorizationException, ServerServiceException {
        return new ArrayList<>(configuration.getProjects());
    }

    @Override
    public Map<ProjectId, List<Role>> getRoles(AuthToken token, UserId userId, GlobalPermissions globalPermissions)
            throws AuthorizationException, ServerServiceException {
        Map<ProjectId, List<Role>> roleMap = new HashMap<>();
        for (Project project : getAllProjects(token)) {
            roleMap.put(project.getId(), getRoles(token, userId, project.getId(), globalPermissions));
        }
        return roleMap;
    }

    @Override
    public List<Role> getRoles(AuthToken token, UserId userId, ProjectId projectId, GlobalPermissions globalPermissions)
            throws AuthorizationException, ServerServiceException {
        return new ArrayList<>(configuration.getRoles(userId, projectId, globalPermissions));
    }

    @Override
    public List<Role> getAllRoles(AuthToken token) throws AuthorizationException, ServerServiceException {
        return new ArrayList<>(configuration.getRoles());
    }

    @Override
    public Map<ProjectId, List<Operation>> getOperations(AuthToken token, UserId userId, GlobalPermissions globalPermissions)
            throws AuthorizationException, ServerServiceException {
        Map<ProjectId, List<Operation>> operationMap = new HashMap<>();
        for (Project project : getAllProjects(token)) {
            operationMap.put(project.getId(), getOperations(token, userId, project.getId(), globalPermissions));
        }
        return operationMap;
    }

    @Override
    public List<Operation> getOperations(AuthToken token, UserId userId, ProjectId projectId, GlobalPermissions globalPermissions)
            throws AuthorizationException, ServerServiceException {
        return new ArrayList<>(configuration.getOperations(userId, projectId, globalPermissions));
    }

    @Override
    public List<Operation> getOperations(AuthToken token, RoleId roleId)
            throws AuthorizationException, ServerServiceException {
        try {
            return new ArrayList<>(configuration.getOperations(configuration.getRole(roleId)));
        }
        catch (UnknownRoleIdException e) {
            logger.error(printLog(token.getUser(), "List operations", e.getMessage()));
            throw new ServerServiceException(e.getMessage(), e);
        }
    }

    @Override
    public List<Operation> getAllOperations(AuthToken token) throws AuthorizationException, ServerServiceException {
        return new ArrayList<>(configuration.getOperations());
    }

    @Override
    public boolean isOperationAllowed(AuthToken token, OperationId operationId, ProjectId projectId, UserId userId)
            throws AuthorizationException, ServerServiceException {
        return configuration.isOperationAllowed(operationId, projectId, userId);
    }

    @Override
    public boolean isOperationAllowed(AuthToken token, OperationId operationId, UserId userId)
            throws AuthorizationException, ServerServiceException {
        return configuration.isOperationAllowed(operationId, userId);
    }

    private void saveChanges() throws ServerServiceException {
        synchronized (configurationFile) {
            try {
                manager.saveConfiguration(configurationFile);
                configuration = manager.getConfiguration();
            }
            catch (IOException e) {
                String message = "Unable to save server configuration";
                logger.error(printLog(null, "Save configuration", message), e);
                throw new ServerServiceException(message, e);
            }
        }
    }
}
