package org.nrg.containers.initialization.tasks;

import lombok.extern.slf4j.Slf4j;
import org.nrg.containers.model.command.entity.CommandVisibility;
import org.nrg.framework.orm.DatabaseHelper;
import org.nrg.xft.schema.XFTManager;
import org.nrg.xnat.initialization.tasks.AbstractInitializingTask;
import org.nrg.xnat.initialization.tasks.InitializingTaskException;
import org.nrg.xnat.services.XnatAppInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class UpdateVisibilityOfExistingCommands extends AbstractInitializingTask {

    @Autowired
    public UpdateVisibilityOfExistingCommands(final XnatAppInfo appInfo, final JdbcTemplate template) {
        super();
        this.appInfo                   = appInfo;
        this.databaseHelper            = new DatabaseHelper(template);
        this.jdbcTemplate              = template;
    }

    @Override
    public String getTaskName() {
        return "UpdateVisibilityOfExistingCommands";
    }

    @Override
    protected void callImpl() throws InitializingTaskException {
        try {
            if (!appInfo.isInitialized() || !XFTManager.isComplete()) {
                throw new InitializingTaskException(InitializingTaskException.Level.RequiresInitialization);
            }
            jdbcTemplate.execute("UPDATE " + COMMAND_ENTITY_TABLE_NAME + " SET " + VISIBILITY_COLUMN + " = 0 where " + VISIBILITY_COLUMN + " is null");
            log.debug("Table " + COMMAND_ENTITY_TABLE_NAME + " updated. Set the " + VISIBILITY_COLUMN + " column value to " + CommandVisibility.PUBLIC_CONTAINER);
        } catch(Exception e) {
            log.error("Encountered while setting visibility column value",e);
            throw new InitializingTaskException(InitializingTaskException.Level.Error);
        }
    }

    private final JdbcTemplate jdbcTemplate;
    private final DatabaseHelper databaseHelper;
    private final XnatAppInfo appInfo;
    private final String COMMAND_ENTITY_TABLE_NAME = "xhbm_command_entity";
    private final String VISIBILITY_COLUMN = "visibility_type";


}
