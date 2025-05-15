package org.nrg.containers.initialization.tasks;

import lombok.extern.slf4j.Slf4j;
import org.nrg.framework.orm.DatabaseHelper;
import org.nrg.xnat.initialization.tasks.AbstractInitializingTask;
import org.nrg.xnat.initialization.tasks.InitializingTaskException;
import org.nrg.xnat.services.XnatAppInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.SQLException;

@Component
@Slf4j
public class UpdateOrchestrationTable extends AbstractInitializingTask {

    @Autowired
    public UpdateOrchestrationTable(final XnatAppInfo appInfo, final JdbcTemplate template) {
        super();
        this.appInfo                   = appInfo;
        this.databaseHelper            = new DatabaseHelper(template);
        this.jdbcTemplate              = template;
    }

    @Override
    public String getTaskName() {
        return "Update ";
    }

    @Override
    protected void callImpl() throws InitializingTaskException {
        try {
            if (!appInfo.isInitialized() || !databaseHelper.tableExists("xhbm_orchestration_entity")) {
                throw new InitializingTaskException(InitializingTaskException.Level.RequiresInitialization);
            }
            if (null == databaseHelper.columnExists(ORCHESTRATION_TABLE_NAME, HALT_ON_COMMAND_FAILURE_COLUMN)) {
                //Create the column and set its value to true for all rows (the default behaviour of orchestration)
                jdbcTemplate.execute("ALTER TABLE " + ORCHESTRATION_TABLE_NAME + " ADD COLUMN " + HALT_ON_COMMAND_FAILURE_COLUMN + " " + HALT_ON_COMMAND_FAILURE_COLUMN_TYPE);
                jdbcTemplate.execute("UPDATE " + ORCHESTRATION_TABLE_NAME + " SET " + HALT_ON_COMMAND_FAILURE_COLUMN + " = true");
                log.debug("Table " + ORCHESTRATION_TABLE_NAME + " updated. Added column " + HALT_ON_COMMAND_FAILURE_COLUMN + " of type " + HALT_ON_COMMAND_FAILURE_COLUMN_TYPE + ". Set the column value to true");
            }

        } catch (SQLException e) {
            throw new InitializingTaskException(InitializingTaskException.Level.RequiresInitialization);
        }
    }

    private final JdbcTemplate jdbcTemplate;
    private final DatabaseHelper databaseHelper;
    private final XnatAppInfo appInfo;
    private final String ORCHESTRATION_TABLE_NAME = "xhbm_orchestration_entity";
    private final String HALT_ON_COMMAND_FAILURE_COLUMN = "halt_on_command_failure";
    private final String HALT_ON_COMMAND_FAILURE_COLUMN_TYPE = "boolean";
}
