/*
 * web: org.nrg.xnat.initialization.tasks.transforms.ConvertProjectDataInfoToId
 * XNAT http://www.xnat.org
 * Copyright (c) 2005-2017, Washington University School of Medicine and Howard Hughes Medical Institute
 * All Rights Reserved
 *
 * Released under the Simplified BSD.
 */

package org.nrg.xnat.initialization.tasks.transforms;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.nrg.framework.orm.DatabaseHelper;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.SQLException;
import java.util.concurrent.Callable;

/**
 * Converts a column from JSON to JSONB. If the column is already a JSONB, no action is taken.
 */
@SuppressWarnings("unused")
@Slf4j
public class ConvertJsonToJsonb implements Callable<String> {
    private static final String JSON = "json";
    private static final String JSONB = "jsonb";

    private final DatabaseHelper helper;
    private final String table;
    private final String column;

    public ConvertJsonToJsonb(final DatabaseHelper helper, final String table, final String column) {
        this.helper = helper;
        this.table = table;
        this.column = column;
    }

    @Override
    public String call() {
        try {
            final String typeName = helper.columnExists(table, column);

            // No conversion necessary.
            if (StringUtils.equalsIgnoreCase(typeName, JSONB)) {
                return null;
            }

            // If typeName is anything else, we should not be converting it
            if (!StringUtils.equalsIgnoreCase(typeName, JSON)) {
                return logAndReturn("Request to convert " + table + "." + column + " failed: column is type " + typeName + ", should be " + JSON);
            }

            // Get a JDBC template
            final JdbcTemplate template = helper.getJdbcTemplate();
            if (template == null) {
                return logAndReturn("Request to convert " + table + "." + column + " failed: Unable to get a JDBC template for the database.");
            }

            // Alter the column
            template.execute("ALTER TABLE " + table + " ALTER COLUMN " + column + " SET DATA TYPE " + JSONB + " USING " + column + "::" + JSONB);

            return null;
        } catch (SQLException e) {
            return logAndReturn("An error occurred trying to convert " + table + "." + column + " from JSON to JSONB: " + e.getMessage());
        }
    }

    private String logAndReturn(final String message) {
        log.warn(message);
        return message;
    }
}
