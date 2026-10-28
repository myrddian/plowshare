package io.aeyer.plowshare.server.pool;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.AbstractDataSource;

/**
 * Transparent diagnostic decorator outside the existing admission/transaction source. It owns no
 * connections or permits. Driver unwrap, cancellation, close and original exception identity stay
 * intact. Only execute calls are timed; result iteration and connection acquisition are separate.
 * Prepared batches use their prepared fingerprint; unprepared batches use an opaque batch marker.
 */
public final class TracedDataSource extends AbstractDataSource {
  private final DataSource source;
  private final JdbcExecutionTrace trace;

  public TracedDataSource(DataSource source, JdbcExecutionTrace trace) {
    this.source = Objects.requireNonNull(source);
    this.trace = Objects.requireNonNull(trace);
  }

  public Connection getConnection() throws SQLException {
    return connection(source.getConnection());
  }

  public Connection getConnection(String user, String password) throws SQLException {
    return connection(source.getConnection(user, password));
  }

  private Connection connection(Connection connection) {
    return (Connection)
        Proxy.newProxyInstance(
            Connection.class.getClassLoader(),
            new Class<?>[] {Connection.class},
            (proxy, method, args) -> {
              var result = invoke(proxy, connection, method, args);
              if (result instanceof Statement statement
                  && (method.getName().equals("prepareStatement")
                      || method.getName().equals("prepareCall")
                      || method.getName().equals("createStatement"))) {
                return statement(
                    statement,
                    (Connection) proxy,
                    args != null && args.length > 0 && args[0] instanceof String sql ? sql : null);
              }
              return result;
            });
  }

  private Statement statement(Statement statement, Connection connection, String preparedSql) {
    Class<?> type =
        statement instanceof CallableStatement
            ? CallableStatement.class
            : statement instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
    return (Statement)
        Proxy.newProxyInstance(
            Statement.class.getClassLoader(),
            new Class<?>[] {type},
            (proxy, method, args) -> {
              if (method.getName().equals("getConnection")) return connection;
              boolean execute =
                  switch (method.getName()) {
                    case "execute",
                            "executeQuery",
                            "executeUpdate",
                            "executeLargeUpdate",
                            "executeBatch",
                            "executeLargeBatch" ->
                        true;
                    default -> false;
                  };
              if (!execute) return invoke(proxy, statement, method, args);
              String sql =
                  preparedSql != null
                      ? preparedSql
                      : args != null && args.length > 0 && args[0] instanceof String value
                          ? value
                          : "STATEMENT_BATCH";
              try (var span = trace.begin(sql, method.getName())) {
                try {
                  return invoke(proxy, statement, method, args);
                } catch (Throwable failure) {
                  span.failed();
                  throw failure;
                }
              }
            });
  }

  private static Object invoke(Object proxy, Object target, Method method, Object[] args)
      throws Throwable {
    if (method.getDeclaringClass() == Object.class) {
      return switch (method.getName()) {
        case "equals" -> proxy == args[0];
        case "hashCode" -> System.identityHashCode(proxy);
        default -> target instanceof Connection ? "TracedConnection" : "TracedStatement";
      };
    }
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException wrapped) {
      throw wrapped.getCause();
    }
  }
}
