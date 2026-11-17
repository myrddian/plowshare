package io.aeyer.plowshare.server.pool;

import com.zaxxer.hikari.HikariDataSource;
import io.aeyer.plowshare.server.pool.ScopedSqlWorkBudget.Scope.Lease;
import io.aeyer.plowshare.server.pool.ScopedSqlWorkBudget.Scope.Query;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.AbstractDataSource;

/**
 * Decorates only checkouts made inside SqlWorkBudget. Unscoped handles are delegated unchanged.
 * Scoped JDBC objects retain their owning connection and materialization timer; no facade or domain
 * service receives JDBC infrastructure from this capability.
 */
final class ScopedSqlDataSource extends AbstractDataSource {
  private final DataSource source;

  private final HikariDataSource pool;

  ScopedSqlDataSource(DataSource source, HikariDataSource pool) {
    this.pool = pool;
    this.source = Objects.requireNonNull(source);
  }

  @FunctionalInterface
  interface Borrow {
    Connection get() throws SQLException;
  }

  @Override
  public Connection getConnection() throws SQLException {
    return borrow(source::getConnection);
  }

  @Override
  public Connection getConnection(String username, String password) throws SQLException {
    return borrow(() -> source.getConnection(username, password));
  }

  private Connection borrow(Borrow selected) throws SQLException {
    var scope = ScopedSqlWorkBudget.current();
    return scope == null ? selected.get() : scope.borrow(selected, pool);
  }

  static Connection connection(Lease lease) {
    return proxy(
        Connection.class,
        (self, method, args) -> {
          String name = method.getName();
          if (name.equals("close")) {
            lease.close();
            return null;
          }
          if (name.equals("abort")) {
            lease.abort();
            return null;
          }
          if (name.equals("unwrap") || name.equals("isWrapperFor"))
            return wrapped(self, method, args, Connection.class);
          lease.check();
          boolean creating =
              name.equals("prepareStatement")
                  || name.equals("prepareCall")
                  || name.equals("createStatement");
          if (creating) lease.statementOpened();
          boolean handedOff = false;
          Query query =
              lease.query(
                  name.equals("commit")
                          || name.equals("rollback")
                          || (name.equals("setAutoCommit") && Boolean.TRUE.equals(args[0]))
                      ? "transaction"
                      : "connection");
          try {
            query.check();
            // Commit may become durable after caller expiry. Refresh its actual backend limit
            // with this transaction command's remaining budget before sending it. Rollback
            // cannot configure settings in an already-aborted PostgreSQL transaction; it keeps
            // the live network/watchdog boundary and owned physical abort instead.
            if (name.equals("commit")
                || (name.equals("setAutoCommit")
                    && Boolean.TRUE.equals(args[0])
                    && !lease.connection.getAutoCommit())) lease.beforeStatement(query);
            Object result = invoke(lease.connection, method, args);
            query.check();
            if (result instanceof Statement statement) {
              handedOff = true;
              return statement(statement, (Connection) self, lease);
            }
            if (result instanceof DatabaseMetaData metadata)
              return metadata(metadata, (Connection) self, lease);
            return result;
          } catch (Throwable failed) {
            query.failed(failed);
            throw failed;
          } finally {
            query.close();
            if (creating && !handedOff) lease.statementClosed();
          }
        });
  }

  private static Statement statement(Statement statement, Connection connection, Lease lease) {
    Class<? extends Statement> type =
        statement instanceof CallableStatement
            ? CallableStatement.class
            : statement instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
    final class State {
      Query query;
      boolean closed;

      void close() {
        if (query != null) {
          query.close();
          query = null;
        }
      }

      void check() throws SQLException {
        lease.check();
        if (query != null) query.check();
      }
    }
    State state = new State();
    return proxy(
        type,
        (self, method, args) -> {
          String name = method.getName();
          if (name.equals("getConnection")) return connection;
          if (name.equals("unwrap") || name.equals("isWrapperFor"))
            return wrapped(self, method, args, type);
          if (name.equals("close")) {
            try {
              return invoke(statement, method, args);
            } finally {
              state.close();
              if (!state.closed) {
                state.closed = true;
                lease.statementClosed();
              }
            }
          }
          state.check();
          boolean execute = name.startsWith("execute");
          Query temporary = null;
          boolean retained = false;
          if (execute) {
            state.close();
            state.query = lease.query("query");
            statement.setQueryTimeout(1);
          } else if (state.query == null || state.query.finished()) {
            temporary = lease.query("statement");
          }
          Query selected = temporary == null ? state.query : temporary;
          try {
            selected.check();
            if (execute) lease.beforeStatement(selected);
            Object result = invoke(statement, method, args);
            selected.check();
            if (result instanceof ResultSet rows) {
              if (temporary != null) {
                state.query = temporary;
                retained = true;
              }
              return result(rows, (Statement) self, selected);
            }
            return result;
          } catch (Throwable failed) {
            try {
              selected.failed(failed);
            } finally {
              if (execute) state.close();
            }
            throw failed;
          } finally {
            if (temporary != null && !retained) temporary.close();
          }
        });
  }

  private static ResultSet result(ResultSet rows, Statement statement, Query query) {
    return proxy(
        ResultSet.class,
        (self, method, args) -> {
          String name = method.getName();
          if (name.equals("getStatement")) return statement;
          if (name.equals("unwrap") || name.equals("isWrapperFor"))
            return wrapped(self, method, args, ResultSet.class);
          if (name.equals("close")) {
            try {
              return invoke(rows, method, args);
            } finally {
              query.close();
            }
          }
          query.check();
          try {
            Object value = invoke(rows, method, args);
            query.check();
            return value;
          } catch (Throwable failed) {
            query.failed(failed);
            throw failed;
          }
        });
  }

  private static DatabaseMetaData metadata(
      DatabaseMetaData metadata, Connection connection, Lease lease) {
    return proxy(
        DatabaseMetaData.class,
        (self, method, args) -> {
          String name = method.getName();
          if (name.equals("getConnection")) return connection;
          if (name.equals("unwrap") || name.equals("isWrapperFor"))
            return wrapped(self, method, args, DatabaseMetaData.class);
          lease.check();
          Query query = lease.query("metadata");
          boolean materializing = false;
          try {
            query.check();
            lease.beforeStatement(query);
            Object value = invoke(metadata, method, args);
            query.check();
            if (value instanceof ResultSet rows) {
              materializing = true;
              return result(rows, null, query);
            }
            return value;
          } catch (Throwable failed) {
            query.failed(failed);
            throw failed;
          } finally {
            if (!materializing) query.close();
          }
        });
  }

  /** Standard wrappers remain scoped; vendor JDBC escape hatches are not supported in this mode. */
  private static Object wrapped(Object self, Method method, Object[] args, Class<?> standard)
      throws SQLException {
    Class<?> requested = (Class<?>) args[0];
    boolean supported = requested.isInstance(self) || requested == standard;
    if (method.getName().equals("isWrapperFor")) return supported;
    if (!supported)
      throw new SQLFeatureNotSupportedException("Native JDBC unwrap is unavailable in scoped work");
    return self;
  }

  @FunctionalInterface
  private interface Call {
    Object invoke(Object self, Method method, Object[] args) throws Throwable;
  }

  private static <T> T proxy(Class<T> type, Call call) {
    return type.cast(
        Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[] {type},
            (self, method, args) -> {
              if (method.getDeclaringClass() == Object.class)
                return switch (method.getName()) {
                  case "equals" -> self == args[0];
                  case "hashCode" -> System.identityHashCode(self);
                  default -> "Scoped" + type.getSimpleName();
                };
              return call.invoke(self, method, args);
            }));
  }

  private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException failed) {
      throw failed.getCause();
    }
  }
}
