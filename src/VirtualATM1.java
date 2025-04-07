

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.sql.*;

// Represents the ATM logic and interacts with the database
// TODO: Consider separating database logic into a DAO (Data Access Object) class
class ATM {
    private int pin; // Current user's PIN
    private double balance;
    private Connection conn;

    // --- Constants ---
    private static final String SELECT_BALANCE_SQL = "SELECT balance FROM users WHERE pin = ?";
    private static final String SELECT_USER_SQL = "SELECT pin FROM users WHERE pin = ?"; // Changed from SELECT *
    private static final String UPDATE_BALANCE_SQL = "UPDATE users SET balance = ? WHERE pin = ?";
    private static final String UPDATE_PIN_SQL = "UPDATE users SET pin = ? WHERE pin = ?";
    private static final String UPDATE_RECIPIENT_BALANCE_SQL = "UPDATE users SET balance = balance + ? WHERE pin = ?";

    /**
     * Constructor for an authenticated user. Fetches balance.
     *
     * @param conn The database connection.
     * @param pin  The user's authenticated PIN.
     * @throws SQLException If the user is not found or a DB error occurs.
     */
    public ATM(Connection conn, int pin) throws SQLException {
        this.conn = conn;
        this.pin = pin; // Store the authenticated PIN

        // TODO: Implement PIN Hashing - Query should be based on username/account ID,
        // PIN comparison happens after fetching the stored hash.
        try (PreparedStatement stmt = conn.prepareStatement(SELECT_BALANCE_SQL)) {
            stmt.setInt(1, pin);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    this.balance = rs.getDouble("balance");
                } else {
                    // This case should technically not happen if authentication succeeded
                    throw new SQLException("User PIN " + pin + " not found after authentication.");
                }
            }
        }
    }

    /**
     * Checks if a user with the given PIN exists.
     * Static method used for checking recipients and during initial auth.
     *
     * @param conn The database connection.
     * @param pinToCheck The PIN to check.
     * @return true if the user exists, false otherwise.
     * @throws SQLException If a database error occurs.
     */
    public static boolean userExists(Connection conn, int pinToCheck) throws SQLException {
        // TODO: Implement PIN Hashing - Query should be based on username/account ID
        try (PreparedStatement stmt = conn.prepareStatement(SELECT_USER_SQL)) {
            stmt.setInt(1, pinToCheck);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next(); // Returns true if a record is found
            }
        }
    }

    /**
     * Authenticates a user based on PIN.
     * Static method used for login.
     *
     * @param conn The database connection.
     * @param pinToCheck The PIN to authenticate.
     * @return true if authentication is successful, false otherwise.
     * @throws SQLException If a database error occurs.
     */
    public static boolean authenticate(Connection conn, int pinToCheck) throws SQLException {
        // TODO: Implement PIN Hashing - Fetch the stored hash for the user
        // and compare it with the hash of pinToCheck using a secure comparison method.
        // For now, just checks existence.
        return userExists(conn, pinToCheck);
    }

    /** Deposits a positive amount into the account. */
    public void deposit(double amount) throws SQLException {
        if (amount <= 0) {
            throw new IllegalArgumentException("Deposit amount must be positive.");
        }
        balance += amount;
        updateBalance();
    }

    /** Withdraws a positive amount if sufficient funds exist. */
    public boolean withdraw(double amount) throws SQLException {
        if (amount <= 0) {
            throw new IllegalArgumentException("Withdrawal amount must be positive.");
        }
        if (amount > balance) {
            return false; // Insufficient funds
        }
        balance -= amount;
        updateBalance();
        return true;
    }

    /** Transfers amount to a recipient if funds and recipient are valid. */
    public boolean transfer(double amount, int recipientPin) throws SQLException {
        if (amount <= 0) {
            throw new IllegalArgumentException("Transfer amount must be positive.");
        }
        if (recipientPin == this.pin) {
            throw new IllegalArgumentException("Cannot transfer to the same account.");
        }
        if (amount > balance) {
            return false; // Insufficient funds
        }
        if (!userExists(conn, recipientPin)) {
            return false; // Recipient does not exist
        }

        // Database transaction (Ideally should be atomic)
        // TODO: Implement proper transaction management (conn.setAutoCommit(false), commit, rollback)
        try {
            // 1. Deduct from sender
            balance -= amount;
            updateBalance();

            // 2. Add to receiver
            try (PreparedStatement stmt = conn.prepareStatement(UPDATE_RECIPIENT_BALANCE_SQL)) {
                stmt.setDouble(1, amount);
                stmt.setInt(2, recipientPin);
                stmt.executeUpdate();
            }
            // If both succeed
            // conn.commit(); // Uncomment if using manual transaction management
            return true;
        } catch (SQLException e) {
            // conn.rollback(); // Uncomment if using manual transaction management
            // Restore balance if deduction happened but transfer failed (simplistic rollback)
            balance += amount; // This might not be perfectly safe without real transactions
            System.err.println("Transfer failed, attempting to rollback balance change. Error: " + e.getMessage());
            throw e; // Re-throw the exception
        }
    }

    /** Changes the user's PIN after verifying the old PIN. */
    public boolean changePin(int oldPinEntered, int newPin) throws SQLException {
        // TODO: Implement PIN Hashing - Compare hash of oldPinEntered with stored hash.
        if (this.pin != oldPinEntered) {
            return false; // Old PIN doesn't match
        }

        // Basic validation for new PIN
        if (String.valueOf(newPin).length() < 4) {
            throw new IllegalArgumentException("New PIN must be at least 4 digits long.");
        }
        if (newPin == oldPinEntered) {
            throw new IllegalArgumentException("New PIN cannot be the same as the old PIN.");
        }

        // TODO: Implement PIN Hashing - Store the hash of newPin.
        try (PreparedStatement stmt = conn.prepareStatement(UPDATE_PIN_SQL)) {
            stmt.setInt(1, newPin);
            stmt.setInt(2, oldPinEntered); // Use the verified old PIN in WHERE clause
            int updated = stmt.executeUpdate();
            if (updated > 0) {
                this.pin = newPin; // Update PIN in the current object state
                return true;
            } else {
                // This might happen if the PIN was changed by another session in the meantime
                System.err.println("PIN change failed - user record may have been modified concurrently.");
                return false;
            }
        }
    }

    /** Returns the current account balance. */
    public double getBalance() {
        return balance;
    }

    /** Updates the user's balance in the database. */
    private void updateBalance() throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(UPDATE_BALANCE_SQL)) {
            stmt.setDouble(1, balance);
            stmt.setInt(2, this.pin); // Use the current user's PIN
            stmt.executeUpdate();
        }
    }
}


// Main GUI class for the Virtual ATM
public class VirtualATM1 extends JFrame {
    private Connection conn;
    private ATM userATM; // Holds the ATM logic instance for the logged-in user

    // --- UI Components ---
    private JTextField pinInputField;
    private JPanel mainPanel; // Panel to switch content (Login / Menu)
    private JLabel headerLabel;

    // --- Constants for UI ---
    private static final String APP_TITLE = "Virtual ATM Machine";
    private static final String BANK_NAME = "DIU Bank";
    private static final Color COLOR_BACKGROUND = Color.decode("#EAE3C3"); // Light beige
    private static final Color COLOR_PANEL = Color.decode("#3B7B7A");      // Teal
    private static final Color COLOR_TEXT_ON_PANEL = Color.BLACK;
    private static final Color COLOR_HEADER_TEXT = Color.decode("#3B7B7A"); // Teal
    private static final Font FONT_HEADER = new Font("Arial", Font.BOLD, 28);
    private static final Font FONT_LABEL = new Font("Arial", Font.BOLD, 18);
    private static final Font FONT_BUTTON = new Font("Arial", Font.BOLD, 16);
    private static final Dimension BUTTON_SIZE = new Dimension(150, 40); // Adjusted button size

    public VirtualATM1() {
        // --- Database Connection ---
        try {
            conn = DBConnection.getConnection(); // Get connection from DBConnection class
        } catch (SQLException e) {
            // Critical failure - cannot proceed without DB
            JOptionPane.showMessageDialog(this,
                    "Database connection failed: " + e.getMessage() + "\nThe application will now exit.",
                    "Database Error", JOptionPane.ERROR_MESSAGE);
            System.exit(1); // Exit if DB connection fails
        }

        // --- Main Window Setup ---
        setTitle(APP_TITLE);
        setSize(500, 400); // Adjusted size
        setMinimumSize(new Dimension(450, 350)); // Prevent shrinking too small
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE); // Handle close manually
        setLayout(new BorderLayout());
        getContentPane().setBackground(COLOR_BACKGROUND);

        // --- Window Closing Hook ---
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                handleExit();
            }
        });

        // --- Header ---
        headerLabel = new JLabel(BANK_NAME, SwingConstants.CENTER);
        headerLabel.setFont(FONT_HEADER);
        headerLabel.setForeground(COLOR_HEADER_TEXT);
        headerLabel.setOpaque(true);
        headerLabel.setBackground(COLOR_BACKGROUND);
        headerLabel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10)); // Add padding
        add(headerLabel, BorderLayout.NORTH);

        // --- Main Content Panel ---
        mainPanel = new JPanel(new CardLayout()); // Use CardLayout to switch views
        mainPanel.setOpaque(false); // Make panel transparent
        add(mainPanel, BorderLayout.CENTER);

        // --- Show Initial Login Screen ---
        showLoginScreen();

        setLocationRelativeTo(null); // Center the window
    }

    // --- Screen Display Methods ---

    /** Sets up and displays the PIN entry screen. */
    private void showLoginScreen() {
        JPanel loginPanel = new JPanel(new GridBagLayout());
        loginPanel.setBackground(COLOR_PANEL);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(15, 15, 15, 15); // Increased padding
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.anchor = GridBagConstraints.LINE_END;

        JLabel pinLabel = new JLabel("Enter PIN:");
        pinLabel.setFont(FONT_LABEL);
        pinLabel.setForeground(COLOR_TEXT_ON_PANEL);
        loginPanel.add(pinLabel, gbc);

        gbc.gridx = 1;
        gbc.anchor = GridBagConstraints.LINE_START;
        pinInputField = new JPasswordField(10); // Use JPasswordField for masking
        pinInputField.setFont(FONT_LABEL);
        // Action listener for Enter key in PIN field
        pinInputField.addActionListener(e -> authenticateUser());
        loginPanel.add(pinInputField, gbc);

        gbc.gridy = 1;
        gbc.gridx = 0;
        gbc.gridwidth = 2; // Span across two columns
        gbc.anchor = GridBagConstraints.CENTER;
        JButton enterButton = createStyledButton("Enter");
        enterButton.addActionListener(e -> authenticateUser());
        loginPanel.add(enterButton, gbc);

        mainPanel.removeAll();
        mainPanel.add(loginPanel, "Login"); // Add with a name
        ((CardLayout) mainPanel.getLayout()).show(mainPanel, "Login"); // Show the login panel
        mainPanel.revalidate();
        mainPanel.repaint();

        // Request focus on the input field when the login screen appears
        SwingUtilities.invokeLater(() -> pinInputField.requestFocusInWindow());
    }

    /** Sets up and displays the main menu screen. */
    private void showMenu() {
        headerLabel.setText(BANK_NAME + " - Main Menu"); // Update header

        JPanel menuPanel = new JPanel();
        menuPanel.setLayout(new GridLayout(3, 2, 20, 20)); // Rows, Cols, HGap, VGap
        menuPanel.setBackground(COLOR_BACKGROUND);
        menuPanel.setBorder(BorderFactory.createEmptyBorder(20, 40, 20, 40)); // Add padding

        String[] options = {"Deposit", "Withdraw", "Transfer", "Balance", "Change PIN", "Exit"};
        for (String option : options) {
            JButton button = createStyledButton(option);
            button.addActionListener(e -> handleMenuSelection(option));
            menuPanel.add(button);
        }

        mainPanel.removeAll();
        mainPanel.add(menuPanel, "Menu");
        ((CardLayout) mainPanel.getLayout()).show(mainPanel, "Menu");
        mainPanel.revalidate();
        mainPanel.repaint();
    }

    // --- Action Handling Methods ---

    /** Attempts to authenticate the user based on the entered PIN. */
    private void authenticateUser() {
        String pinText;
        // Get text from JPasswordField safely
        if (pinInputField instanceof JPasswordField) {
            pinText = new String(((JPasswordField) pinInputField).getPassword());
        } else { // Fallback if somehow it's a JTextField (shouldn't happen)
            pinText = pinInputField.getText();
        }


        if (pinText.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please enter your PIN.", "Input Error", JOptionPane.WARNING_MESSAGE);
            pinInputField.requestFocusInWindow();
            return;
        }

        try {
            int enteredPin = Integer.parseInt(pinText);
            if (ATM.authenticate(conn, enteredPin)) {
                userATM = new ATM(conn, enteredPin); // Create ATM instance for the user
                pinInputField.setText(""); // Clear PIN field
                showMenu(); // Proceed to main menu
            } else {
                JOptionPane.showMessageDialog(this, "Incorrect PIN. Please try again.", "Authentication Failed", JOptionPane.ERROR_MESSAGE);
                pinInputField.setText(""); // Clear PIN field
                pinInputField.requestFocusInWindow();
            }
        } catch (NumberFormatException nfe) {
            JOptionPane.showMessageDialog(this, "Invalid PIN format. Please enter numbers only.", "Input Error", JOptionPane.ERROR_MESSAGE);
            pinInputField.setText(""); // Clear PIN field
            pinInputField.requestFocusInWindow();
        } catch (SQLException se) {
            JOptionPane.showMessageDialog(this, "Database Error during authentication: " + se.getMessage(), "Database Error", JOptionPane.ERROR_MESSAGE);
            se.printStackTrace(); // Log the error
        }
    }

    /** Routes the menu button clicks to the appropriate handler methods. */
    private void handleMenuSelection(String option) {
        try {
            switch (option) {
                case "Deposit":    handleDeposit(); break;
                case "Withdraw":   handleWithdraw(); break;
                case "Transfer":   handleTransfer(); break;
                case "Balance":    showBalance(); break;
                case "Change PIN": handleChangePin(); break;
                case "Exit":       handleExit(); break;
                default:
                    JOptionPane.showMessageDialog(this, "Invalid option selected.", "Error", JOptionPane.ERROR_MESSAGE);
            }
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(this, "A database error occurred: " + e.getMessage(), "Database Error", JOptionPane.ERROR_MESSAGE);
            e.printStackTrace(); // Log detailed error
        } catch (NumberFormatException nfe) {
            JOptionPane.showMessageDialog(this, "Invalid number format entered. Please use numbers only.", "Input Error", JOptionPane.ERROR_MESSAGE);
        } catch (IllegalArgumentException iae) {
            // Catch validation errors from ATM class (e.g., negative amount, bad PIN)
            JOptionPane.showMessageDialog(this, "Invalid input: " + iae.getMessage(), "Input Error", JOptionPane.WARNING_MESSAGE);
        } catch (Exception ex) {
            // Catch-all for unexpected errors
            JOptionPane.showMessageDialog(this, "An unexpected error occurred: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            ex.printStackTrace();
        }
    }

    // --- Specific Action Handlers (using JOptionPane for simplicity) ---

    private void handleDeposit() throws SQLException {
        String amountStr = JOptionPane.showInputDialog(this, "Enter amount to deposit:", "Deposit", JOptionPane.PLAIN_MESSAGE);
        if (amountStr != null && !amountStr.trim().isEmpty()) {
            double amount = Double.parseDouble(amountStr.trim()); // Throws NumberFormatException
            userATM.deposit(amount); // Throws IllegalArgumentException or SQLException
            JOptionPane.showMessageDialog(this, String.format("Successfully deposited $%.2f", amount), "Deposit Successful", JOptionPane.INFORMATION_MESSAGE);
            showBalance(); // Show updated balance
        }
    }

    private void handleWithdraw() throws SQLException {
        String amountStr = JOptionPane.showInputDialog(this, "Enter amount to withdraw:", "Withdraw", JOptionPane.PLAIN_MESSAGE);
        if (amountStr != null && !amountStr.trim().isEmpty()) {
            double amount = Double.parseDouble(amountStr.trim()); // Throws NumberFormatException
            if (userATM.withdraw(amount)) { // Throws IllegalArgumentException or SQLException
                JOptionPane.showMessageDialog(this, String.format("Successfully withdrew $%.2f", amount), "Withdrawal Successful", JOptionPane.INFORMATION_MESSAGE);
                showBalance(); // Show updated balance
            } else {
                JOptionPane.showMessageDialog(this, "Withdrawal failed. Insufficient funds.", "Withdrawal Failed", JOptionPane.WARNING_MESSAGE);
            }
        }
    }

    private void handleTransfer() throws SQLException {
        String recipientStr = JOptionPane.showInputDialog(this, "Enter recipient's PIN:", "Transfer", JOptionPane.PLAIN_MESSAGE);
        if (recipientStr == null || recipientStr.trim().isEmpty()) return;
        int recipientPin = Integer.parseInt(recipientStr.trim()); // Throws NumberFormatException

        String amountStr = JOptionPane.showInputDialog(this, String.format("Enter amount to transfer to PIN %d:", recipientPin), "Transfer", JOptionPane.PLAIN_MESSAGE);
        if (amountStr == null || amountStr.trim().isEmpty()) return;
        double amount = Double.parseDouble(amountStr.trim()); // Throws NumberFormatException

        if (userATM.transfer(amount, recipientPin)) { // Throws IllegalArgumentException or SQLException
            JOptionPane.showMessageDialog(this, String.format("Successfully transferred $%.2f to PIN %d", amount, recipientPin), "Transfer Successful", JOptionPane.INFORMATION_MESSAGE);
            showBalance(); // Show updated balance
        } else {
            // Failure could be due to insufficient funds, invalid recipient, or same account transfer attempt
            // More specific feedback could be given if ATM.transfer returned different codes/exceptions
            JOptionPane.showMessageDialog(this, "Transfer failed. Check recipient PIN, ensure sufficient funds, and that you are not transferring to yourself.", "Transfer Failed", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void handleChangePin() throws SQLException {
        // Use JPasswordField in JOptionPane for better security
        JPasswordField oldPinField = new JPasswordField();
        int oldOpt = JOptionPane.showConfirmDialog(this, oldPinField, "Enter OLD PIN:", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (oldOpt != JOptionPane.OK_OPTION) return;
        int oldPin = Integer.parseInt(new String(oldPinField.getPassword())); // Throws NumberFormatException

        JPasswordField newPinField = new JPasswordField();
        int newOpt = JOptionPane.showConfirmDialog(this, newPinField, "Enter NEW PIN (min 4 digits):", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (newOpt != JOptionPane.OK_OPTION) return;
        int newPin = Integer.parseInt(new String(newPinField.getPassword())); // Throws NumberFormatException

        if (userATM.changePin(oldPin, newPin)) { // Throws IllegalArgumentException or SQLException
            JOptionPane.showMessageDialog(this, "PIN changed successfully.", "PIN Change Successful", JOptionPane.INFORMATION_MESSAGE);
            // Force logout after PIN change for security? Optional.
            // logout();
        } else {
            // This "else" should ideally only be reached if the old PIN didn't match initially,
            // as other failures (length, same PIN, DB error) throw exceptions caught in handleMenuSelection.
            JOptionPane.showMessageDialog(this, "Failed to change PIN. Ensure the OLD PIN is correct.", "PIN Change Failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void showBalance() {
        // Fetches the latest balance from the ATM object
        double currentBalance = userATM.getBalance();
        JOptionPane.showMessageDialog(this, String.format("Your current balance is: $%.2f", currentBalance), "Balance Inquiry", JOptionPane.INFORMATION_MESSAGE);
    }

    /** Handles the exit action, ensuring resources are cleaned up. */
    private void handleExit() {
        int confirm = JOptionPane.showConfirmDialog(this,
                "Are you sure you want to exit?", "Confirm Exit",
                JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);

        if (confirm == JOptionPane.YES_OPTION) {
            System.out.println("Exiting application...");
            DBConnection.closeConnection(); // Close the database connection
            dispose(); // Close the window
            System.exit(0); // Terminate the application
        }
        // If NO_OPTION, do nothing, keeping the window open.
    }

    /** Logs the user out, returning to the login screen. */
    // private void logout() {
    //     userATM = null; // Clear user session
    //     headerLabel.setText(BANK_NAME); // Reset header
    //     showLoginScreen();
    // }


    // --- Utility Methods ---

    /** Creates a JButton with standard styling. */
    private JButton createStyledButton(String text) {
        JButton button = new JButton(text);
        button.setFont(FONT_BUTTON);
        button.setBackground(COLOR_PANEL);
        button.setForeground(COLOR_TEXT_ON_PANEL);
        button.setPreferredSize(BUTTON_SIZE);
        button.setFocusPainted(false); // Improve look
        // Add hover effect (optional)
        button.addMouseListener(new MouseAdapter() {
            public void mouseEntered(MouseEvent evt) {
                button.setBackground(COLOR_PANEL.brighter());
            }
            public void mouseExited(MouseEvent evt) {
                button.setBackground(COLOR_PANEL);
            }
        });
        return button;
    }

    // --- Main Method ---
    public static void main(String[] args) {
        // Set Look and Feel (optional, for better UI consistency)
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            System.err.println("Failed to set system look and feel.");
        }

        // Run the GUI on the Event Dispatch Thread (EDT)
        SwingUtilities.invokeLater(() -> {
            VirtualATM1 atmApp = new VirtualATM1();
            atmApp.setVisible(true);
        });
    }
}
