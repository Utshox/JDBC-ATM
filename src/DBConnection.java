import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class DBConnection {
    // Database URL - Make sure atm.db is accessible
    private static final String DB_URL = "jdbc:sqlite:E:\\Temp\\ATM_project\\atm.db";
    private static Connection conn = null;

    // Private constructor to prevent instantiation
    private DBConnection() {}

    /**
     * Gets the singleton database connection instance.
     * Initializes the connection if it hasn't been created yet.
     *
     * @return The database connection.
     * @throws SQLException If a database access error occurs.
     */
    public static Connection getConnection() throws SQLException {
        if (conn == null || conn.isClosed()) {
            try {
                // Load the SQLite JDBC driver (optional for modern JDBC)
                // Class.forName("org.sqlite.JDBC");
                conn = DriverManager.getConnection(DB_URL);
                System.out.println("Database connection established."); // Log connection
            } catch (SQLException e) {
                System.err.println("Failed to connect to the database: " + e.getMessage());
                throw e; // Re-throw exception to be handled by caller
            }
            // } catch (ClassNotFoundException e) {
            //     System.err.println("SQLite JDBC Driver not found: " + e.getMessage());
            //     throw new SQLException("JDBC Driver not found", e);
            // }
        }
        return conn;
    }

    /**
     * Closes the database connection if it is open.
     */
    public static void closeConnection() {
        if (conn != null) {
            try {
                if (!conn.isClosed()) {
                    conn.close();
                    System.out.println("Database connection closed."); // Log closure
                }
            } catch (SQLException e) {
                System.err.println("Error closing the database connection: " + e.getMessage());
            } finally {
                conn = null; // Allow re-connection if needed later
            }
        }
    }

    // Optional: Main method for testing the connection
    public static void main(String[] args) {
        try {
            Connection testConn = DBConnection.getConnection();
            if (testConn != null && !testConn.isClosed()) {
                System.out.println("Connection test successful.");
                DBConnection.closeConnection();
            } else {
                System.out.println("Connection test failed.");
            }
        } catch (SQLException e) {
            System.err.println("Connection test failed with error: " + e.getMessage());
        }
    }
}