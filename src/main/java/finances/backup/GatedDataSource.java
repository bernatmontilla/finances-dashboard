package finances.backup;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/** The application's data source: every connection it hands out holds the {@link DatabaseGate} until it is closed. */
public final class GatedDataSource extends DelegatingDataSource {

    private final DatabaseGate gate;

    public GatedDataSource(DataSource target, DatabaseGate gate) {
        super(target);
        this.gate = gate;
    }

    public DatabaseGate gate() {
        return gate;
    }

    @Override
    public Connection getConnection() throws SQLException {
        gate.enter();
        try {
            return guarded(super.getConnection());
        } catch (SQLException | RuntimeException e) {
            gate.leave();
            throw e;
        }
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        gate.enter();
        try {
            return guarded(super.getConnection(username, password));
        } catch (SQLException | RuntimeException e) {
            gate.leave();
            throw e;
        }
    }

    private Connection guarded(Connection connection) {
        AtomicBoolean open = new AtomicBoolean(true);
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getName().equals("close") && method.getParameterCount() == 0) {
                try {
                    connection.close();
                } finally {
                    if (open.getAndSet(false)) {
                        gate.leave();
                    }
                }
                return null;
            }
            try {
                return method.invoke(connection, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        };
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, handler);
    }
}
