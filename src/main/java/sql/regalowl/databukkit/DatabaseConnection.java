package regalowl.databukkit;


import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;




public abstract class DatabaseConnection {

	protected DataBukkit dab;
	protected DatabaseConnection dc;
	protected Connection connection;
	protected CopyOnWriteArrayList<String> statements = new CopyOnWriteArrayList<String>();
	protected String currentStatement;
	protected PreparedStatement preparedStatement;
	protected AtomicBoolean logWriteErrors = new AtomicBoolean();
	protected AtomicBoolean logReadErrors = new AtomicBoolean();
	
    protected AtomicBoolean shutDownOverride = new AtomicBoolean();
	
	DatabaseConnection(DataBukkit dab, boolean override) {
		dc = this;
		this.dab = dab;
		this.shutDownOverride.set(override);
	}
	
	public void write(List<String> sql, boolean logErrors) {
		try {
			boolean logSQL = dab.getSQLWrite().logSQL();
			logWriteErrors.set(logErrors);
			if (connection == null || connection.isClosed() || connection.isReadOnly()) {openConnection();}
			for (String csql : sql) {statements.add(csql);}
			if (statements.size() == 0) {return;}
			connection.setAutoCommit(false);
			for (String statement : statements) {
				currentStatement = statement;
				if (logSQL) {dab.getSQLWrite().logSQL(currentStatement);}
				preparedStatement = connection.prepareStatement(currentStatement);
				preparedStatement.executeUpdate();
			}
			if (dab.getSQLWrite().shutdownStatus().get() && !shutDownOverride.get()) {
				connection.rollback();
			} else {
				connection.commit();
			}
		} catch (SQLException e) {
			try {
				connection.rollback();
				if (logWriteErrors.get()) {
					dab.writeError(e, "SQL write failed.  The failing SQL statement is in the following brackets: [" + currentStatement + "]");
				}
				statements.remove(currentStatement);
				dab.getSQLWrite().addToQueue(statements);
			} catch (SQLException e1) {
				dab.writeError(e, "Rollback failed.  Cannot recover. Data loss may have occurred.");
			}
		} finally {
			statements.clear();
			if (!shutDownOverride.get()) {
				dab.getSQLWrite().returnConnection(dc);
			}
		}
	}
	
	
	/**
	 * This function should be run asynchronously to prevent slowing the main thread.
	 * @param statement
	 * @return QueryResult
	 */
	public QueryResult read(String statement, boolean logErrors) {
		logReadErrors.set(logErrors);
		QueryResult qr = new QueryResult();
		try {
			if (connection == null || connection.isClosed()) {openConnection();}
			Statement state = connection.createStatement();
			ResultSet resultSet = state.executeQuery(statement);
			ResultSetMetaData rsmd = resultSet.getMetaData();
			int columnCount = rsmd.getColumnCount();
			for (int i = 1; i <= columnCount; i++) {
				qr.addColumnName(rsmd.getColumnLabel(i));
			}
			while (resultSet.next()) {
				for (int i = 1; i <= columnCount; i++) {
					qr.addData(i, resultSet.getString(i));
				}
			}
			resultSet.close();
			state.close();
			statement = null;
			return qr;
		} catch (SQLException e) {
			if (logReadErrors.get()) {
				dab.writeError(e, "The failed SQL statement is in the following brackets: [" + statement + "]");
			}
			return qr;
		} finally {
			dab.getSQLRead().returnConnection(dc);
		}
	}
	
	
	
	protected abstract void openConnection();
	
	public void closeConnection() {
		try {
			connection.close();
		} catch (Exception e) {}
	}


	
}