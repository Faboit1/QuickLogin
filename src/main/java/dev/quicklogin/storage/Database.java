package dev.quicklogin.storage;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.EnumSet;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Thin, dependency-free SQLite storage layer.
 *
 * <p>A single {@link Connection} is used and every access is synchronized on
 * {@link #lock}. Login lookups are fast and infrequent, so a connection pool
 * would be over-engineering; WAL mode keeps reads from blocking the writer.
 *
 * <p>All methods are safe to call from asynchronous threads.
 */
public final class Database implements AutoCloseable {

    private final Logger logger;
    private final File file;
    private final Object lock = new Object();
    private Connection connection;

    public Database(File file, Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    public void connect() throws SQLException {
        synchronized (lock) {
            // The relocated driver registers itself; loading the class is enough.
            try {
                Class.forName("dev.quicklogin.libs.sqlite.JDBC");
            } catch (ClassNotFoundException ignored) {
                // Fall back to the un-relocated name (useful when running tests).
                try {
                    Class.forName("org.sqlite.JDBC");
                } catch (ClassNotFoundException e) {
                    throw new SQLException("SQLite JDBC driver not found on the classpath", e);
                }
            }

            connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA synchronous=NORMAL");
                st.execute("PRAGMA foreign_keys=ON");
                st.execute("PRAGMA busy_timeout=5000");
                st.executeUpdate(
                        "CREATE TABLE IF NOT EXISTS accounts (" +
                        "  username_lower TEXT PRIMARY KEY," +
                        "  username       TEXT NOT NULL," +
                        "  premium_uuid   TEXT," +
                        "  premium        INTEGER NOT NULL DEFAULT 0," +
                        "  password       TEXT," +
                        "  first_seen     INTEGER NOT NULL," +
                        "  last_login     INTEGER NOT NULL DEFAULT 0" +
                        ")");
            }
            restrictFilePermissions();
        }
    }

    /** Best-effort: keep the database (which holds generated passwords) owner-only. */
    private void restrictFilePermissions() {
        try {
            Set<PosixFilePermission> perms = EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(file.toPath(), perms);
        } catch (Exception ignored) {
            // Non-POSIX filesystem (e.g. Windows) — silently skip.
        }
    }

    public PlayerData find(String usernameLower) {
        synchronized (lock) {
            String sql = "SELECT username_lower, username, premium_uuid, premium, password, first_seen, last_login " +
                    "FROM accounts WHERE username_lower = ?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, usernameLower);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    return new PlayerData(
                            rs.getString("username_lower"),
                            rs.getString("username"),
                            rs.getString("premium_uuid"),
                            rs.getInt("premium") != 0,
                            rs.getString("password"),
                            rs.getLong("first_seen"),
                            rs.getLong("last_login"));
                }
            } catch (SQLException e) {
                logger.severe("Failed to read account '" + usernameLower + "': " + e.getMessage());
                return null;
            }
        }
    }

    /** Insert or update an account record. */
    public void save(PlayerData data) {
        synchronized (lock) {
            String sql = "INSERT INTO accounts " +
                    "(username_lower, username, premium_uuid, premium, password, first_seen, last_login) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?) " +
                    "ON CONFLICT(username_lower) DO UPDATE SET " +
                    "  username    = excluded.username," +
                    "  premium_uuid= excluded.premium_uuid," +
                    "  premium     = excluded.premium," +
                    "  password    = excluded.password," +
                    "  last_login  = excluded.last_login";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, data.usernameLower());
                ps.setString(2, data.username());
                ps.setString(3, data.premiumUuid());
                ps.setInt(4, data.premium() ? 1 : 0);
                ps.setString(5, data.password());
                ps.setLong(6, data.firstSeen());
                ps.setLong(7, data.lastLogin());
                ps.executeUpdate();
            } catch (SQLException e) {
                logger.severe("Failed to save account '" + data.usernameLower() + "': " + e.getMessage());
            }
        }
    }

    public void updateLastLogin(String usernameLower, long when) {
        synchronized (lock) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE accounts SET last_login = ? WHERE username_lower = ?")) {
                ps.setLong(1, when);
                ps.setString(2, usernameLower);
                ps.executeUpdate();
            } catch (SQLException e) {
                logger.warning("Failed to update last_login for '" + usernameLower + "': " + e.getMessage());
            }
        }
    }

    /** Remove the stored password so the account is re-registered on next join. */
    public boolean delete(String usernameLower) {
        synchronized (lock) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM accounts WHERE username_lower = ?")) {
                ps.setString(1, usernameLower);
                return ps.executeUpdate() > 0;
            } catch (SQLException e) {
                logger.warning("Failed to delete account '" + usernameLower + "': " + e.getMessage());
                return false;
            }
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException ignored) {
                }
                connection = null;
            }
        }
    }
}
