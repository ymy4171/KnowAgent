package com.knowagent.security.infrastructure.persistence.typehandler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.postgresql.util.PGobject;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

@MappedJdbcTypes(JdbcType.OTHER)
public final class PermissionSetJsonbTypeHandler extends BaseTypeHandler<Set<String>> {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public void setNonNullParameter(PreparedStatement statement, int index, Set<String> parameter, JdbcType jdbcType)
            throws SQLException {
        PGobject jsonb = new PGobject();
        jsonb.setType("jsonb");
        jsonb.setValue(write(parameter));
        statement.setObject(index, jsonb);
    }

    @Override
    public Set<String> getNullableResult(ResultSet resultSet, String columnName) throws SQLException {
        return read(resultSet.getString(columnName));
    }

    @Override
    public Set<String> getNullableResult(ResultSet resultSet, int columnIndex) throws SQLException {
        return read(resultSet.getString(columnIndex));
    }

    @Override
    public Set<String> getNullableResult(CallableStatement statement, int columnIndex) throws SQLException {
        return read(statement.getString(columnIndex));
    }

    static String write(Set<String> permissions) throws SQLException {
        validate(permissions);
        try {
            return OBJECT_MAPPER.writeValueAsString(new TreeSet<>(permissions));
        } catch (JsonProcessingException exception) {
            throw new SQLException("Unable to serialize role permissions", exception);
        }
    }

    static Set<String> read(String value) throws SQLException {
        if (value == null) {
            return null;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(value);
            if (!root.isArray()) {
                throw new SQLException("Role permissions JSONB must be an array");
            }
            Set<String> permissions = new LinkedHashSet<>();
            for (JsonNode item : root) {
                if (!item.isTextual() || item.textValue().isBlank()) {
                    throw new SQLException("Role permission entries must be non-blank strings");
                }
                permissions.add(item.textValue());
            }
            return Collections.unmodifiableSet(permissions);
        } catch (JsonProcessingException exception) {
            throw new SQLException("Unable to deserialize role permissions", exception);
        }
    }

    private static void validate(Set<String> permissions) throws SQLException {
        for (String permission : permissions) {
            if (permission == null || permission.isBlank()) {
                throw new SQLException("Role permission entries must be non-blank strings");
            }
        }
    }
}
