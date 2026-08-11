package com.knowagent.security.infrastructure.persistence.typehandler;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;
import org.postgresql.util.PGobject;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

@MappedTypes(InetAddress.class)
@MappedJdbcTypes(JdbcType.OTHER)
public final class PostgresInetTypeHandler extends BaseTypeHandler<InetAddress> {
    @Override
    public void setNonNullParameter(PreparedStatement statement, int index, InetAddress parameter, JdbcType jdbcType)
            throws SQLException {
        PGobject inet = new PGobject();
        inet.setType("inet");
        inet.setValue(parameter.getHostAddress());
        statement.setObject(index, inet);
    }

    @Override
    public InetAddress getNullableResult(ResultSet resultSet, String columnName) throws SQLException {
        return read(resultSet.getString(columnName));
    }

    @Override
    public InetAddress getNullableResult(ResultSet resultSet, int columnIndex) throws SQLException {
        return read(resultSet.getString(columnIndex));
    }

    @Override
    public InetAddress getNullableResult(CallableStatement statement, int columnIndex) throws SQLException {
        return read(statement.getString(columnIndex));
    }

    static InetAddress read(String value) throws SQLException {
        if (value == null) {
            return null;
        }
        int prefixIndex = value.indexOf('/');
        String address = prefixIndex >= 0 ? value.substring(0, prefixIndex) : value;
        try {
            return InetAddress.getByName(address);
        } catch (UnknownHostException exception) {
            throw new SQLException("Unable to deserialize PostgreSQL inet value", exception);
        }
    }
}
