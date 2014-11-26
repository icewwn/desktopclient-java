/*
 *  Kontalk Java client
 *  Copyright (C) 2014 Kontalk Devteam <devteam@kontalk.org>
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.kontalk;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.lang.StringUtils;
import org.kontalk.model.KonMessage;
import org.kontalk.model.KonThread;
import org.kontalk.model.User;
import org.sqlite.SQLiteConfig;

/**
 * Global database for permanently storing all model information.
 * Uses the JDBC API and SQLite as DBMS.
 * @author Alexander Bikadorov <abiku@cs.tu-berlin.de>
 */
public final class Database {
    private final static Logger LOGGER = Logger.getLogger(Database.class.getName());

    private static Database INSTANCE = null;
    private final String DB_NAME = "kontalk_db.sqlite";

    private Connection mConn = null;

    private Database(String path) throws KonException {
        // load the sqlite-JDBC driver using the current class loader
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException ex) {
            LOGGER.log(Level.SEVERE, "sqlite-JDBC driver not found", ex);
            throw new KonException(KonException.Error.DB, ex);
        }

        // create database connection
        String filePath = path + "/" + DB_NAME;
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        try {
          mConn = DriverManager.getConnection("jdbc:sqlite:" + filePath, config.toProperties());
        } catch(SQLException ex) {
          // if the error message is "out of memory",
          // it probably means no database file is found
          LOGGER.log(Level.SEVERE, "can't create database connection", ex);
          throw new KonException(KonException.Error.DB, ex);
        }

        try {
            mConn.setAutoCommit(true);
        } catch (SQLException ex) {
            LOGGER.log(Level.WARNING, "can't set autocommit", ex);
        }

        // make sure tables are created
        String create = "CREATE TABLE IF NOT EXISTS ";
        try (Statement stat = mConn.createStatement()) {
            stat.executeUpdate(create + User.TABLE + " " + User.CREATE_TABLE);
            stat.executeUpdate(create +
                    KonThread.TABLE +
                    " " +
                    KonThread.CREATE_TABLE);
            stat.executeUpdate(create +
                    KonThread.TABLE_RECEIVER +
                    " " +
                    KonThread.CREATE_TABLE_RECEIVER);
            stat.executeUpdate(create +
                    KonMessage.TABLE +
                    " " +
                    KonMessage.CREATE_TABLE);
        } catch (SQLException ex) {
            LOGGER.log(Level.SEVERE, "can't create tables", ex);
            throw new KonException(KonException.Error.DB, ex);
        }
    }

    public void close() {
        if(mConn == null)
            return;
        try {
            mConn.close();
        } catch(SQLException ex) {
            // connection close failed.
            System.err.println(ex);
        }
    }

    /**
     * Select all rows from one table.
     * The returned ResultSet must be closed by the caller after usage!
     */
    public ResultSet execSelectAll(String table) throws SQLException {
        return execSelect("SELECT * FROM " + table);
    }

    /**
     * Select rows from one table that match an arbitrary 'where' clause.
     * Insecure to SQL injections, use with caution!
     * The returned ResultSet must be closed by the caller after usage!
     */
    public ResultSet execSelectWhereInsecure(String table, String where) throws SQLException {
        return execSelect("SELECT * FROM " + table + " WHERE " + where);
    }

    private ResultSet execSelect(String select) throws SQLException {
        try {
            PreparedStatement stat = mConn.prepareStatement(select);
            // does not work, i dont care
            //stat.closeOnCompletion();
            ResultSet resultSet = stat.executeQuery();
            return resultSet;
        } catch (SQLException ex) {
            LOGGER.log(Level.WARNING, "can't execute select: " + select, ex);
            throw ex;
        }
    }

    /**
     * Get the number of rows in one table that match a WHERE clause
     * with one 'key' == 'value' condition.
     * @return number of rows for a SELECT/count query, -1 if something went wrong
     */
    public int execCount(String table, String key, Object value) {
        String select = "SELECT count(*) FROM " + table + " WHERE " + key + " = ?";
        try {
            PreparedStatement stat = mConn.prepareStatement(select);
            setValue(stat, 0, value);
            ResultSet resultSet = stat.executeQuery();
            resultSet.last();
            return resultSet.getRow();
        } catch (SQLException ex) {
            LOGGER.log(Level.WARNING, "can't execute select: " + select, ex);
            return -1;
        }
    }

    /**
     * Add new model to database.
     * @param table table name the values are inserted into
     * @param values arbitrary objects that are inserted
     * @return id value of inserted row, 0 if something went wrong
     */
    public int execInsert(String table, List<Object> values) {
        // first column is the id
        String insert = "INSERT INTO " + table + " VALUES (NULL,";

        List<String> vList = new ArrayList<>(values.size());
        while(vList.size() < values.size())
            vList.add("?");

        insert += StringUtils.join(vList, ", ") + ")";

        try (PreparedStatement stat = mConn.prepareStatement(insert,
                Statement.RETURN_GENERATED_KEYS)) {
            insertValues(stat, values);
            stat.executeUpdate();
            ResultSet keys = stat.getGeneratedKeys();
            return keys.getInt(1);
        } catch (SQLException ex) {
            LOGGER.log(Level.WARNING, "can't execute insert: " + insert + " " + values, ex);
            return 0;
        }
    }

    /**
     * Update values (at most one row)
     * @param table
     * @param set
     * @param id
     * @return id value of updated row, 0 if something went wrong
     */
    public int execUpdate(String table, Map<String, Object> set, int id) {
        String update = "UPDATE OR FAIL " + table + " SET ";

        List<String> keyList = new ArrayList<>(set.keySet());

        List<String> vList = new ArrayList<>(keyList.size());
        for (String key : keyList)
            vList.add(key + " = ?");

        update += StringUtils.join(vList, ", ") + " WHERE _id == " + id ;
        // note: looks like driver doesn't support "LIMIT"
        //update += " LIMIT 1";

        try (PreparedStatement stat = mConn.prepareStatement(update, Statement.RETURN_GENERATED_KEYS)) {
            insertValues(stat, keyList, set);
            stat.executeUpdate();
            ResultSet keys = stat.getGeneratedKeys();
            return keys.getInt(1);
        } catch (SQLException ex) {
            LOGGER.log(Level.WARNING, "can't execute update: " + update + " " + set, ex);
            return 0;
        }
    }

    public boolean execDelete(String table, int id) {
        LOGGER.info("deleting id "+id+" from table "+table);
        try (Statement stat = mConn.createStatement()) {
            stat.executeUpdate("DELETE FROM " + table + " WHERE _id = " + id);
        } catch (SQLException ex) {
            LOGGER.log(Level.WARNING, "can't delete", ex);
            return false;
        }
        return true;
    }

    private void insertValues(PreparedStatement stat,
            List<String> keys,
            Map<String, Object> map) throws SQLException {
        for (int i = 0; i < keys.size(); i++) {
            setValue(stat, i, map.get(keys.get(i)));
         }
    }

    private void insertValues(PreparedStatement stat,
            List<Object> values) throws SQLException {
        for (int i = 0; i < values.size(); i++) {
            setValue(stat, i, values.get(i));
        }
    }

    private void setValue(PreparedStatement stat, int i, Object value)
            throws SQLException {
        if (value instanceof String) {
                stat.setString(i+1, (String) value);
            } else if (value instanceof Integer) {
                stat.setInt(i+1, (int) value);
            } else if (value instanceof Date) {
                stat.setLong(i+1, ((Date) value).getTime());
            } else if (value instanceof Boolean) {
                stat.setBoolean(i+1, (boolean) value);
            } else if (value instanceof Enum) {
                stat.setInt(i+1, ((Enum) value).ordinal());
            } else if (value instanceof EnumSet) {
                stat.setInt(i+1, this.enumSetToInt(((EnumSet) value)));
            } else if (value == null) {
                stat.setNull(i+1, Types.NULL);
            } else {
                LOGGER.warning("unknown type: " + value);
            }
    }

    /**
     * Encode an enum set to an integer representing a bit array.
     */
    private int enumSetToInt(EnumSet<?> enumSet) {
        int b = 0;
        for (Object o: enumSet) {
            b += 1 << ((Enum) o).ordinal();
        }
        return b;
    }

    /**
     * Get an enum set by parsing an integer which represents a bit array.
     * Source: http://stackoverflow.com/questions/2199399/storing-enumset-in-a-database
     * @param <T> type of elements in enum set
     * @param enumClass enum class to determine the type
     * @param decoded integer decoded as
     * @return an enum set containing the enums specified by the integer
     */
    public static <T extends Enum<T>> EnumSet<T> intToEnumSet(Class<T> enumClass, int decoded) {
        EnumSet<T> enumSet = EnumSet.noneOf(enumClass);
        T[] enums = enumClass.getEnumConstants();
        while (decoded != 0) {
            int ordinal = Integer.numberOfTrailingZeros(decoded);
            enumSet.add(enums[ordinal]);
            decoded -= Integer.lowestOneBit(decoded);
        }
        return enumSet;
    }

    public static void initialize(String path) throws KonException {
        INSTANCE = new Database(path);
    }

    public static Database getInstance() {
        if (INSTANCE == null) {
            LOGGER.warning("database not initialized");
            throw new RuntimeException();
        }
        return INSTANCE;
    }

}
