package com.knowagent.knowledge.infrastructure.persistence.typehandler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.postgresql.util.PGobject;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;

/**
 * Maps the {@code metadata} JSONB object to/from a {@link Map}{@code <String,String>} of
 * chunk metadata (e.g. the token-estimator algorithm version). A null column decodes to
 * null; the domain model treats absent metadata as the empty map.
 */
@MappedJdbcTypes(JdbcType.OTHER)
public final class StringMapJsonbTypeHandler extends BaseTypeHandler<Map<String, String>> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, String>> MAP_TYPE = new TypeReference<>() {
    };

    @Override
    public void setNonNullParameter(PreparedStatement statement, int index, Map<String, String> parameter,
                                    JdbcType jdbcType) throws SQLException {
        PGobject jsonb = new PGobject();
        jsonb.setType("jsonb");
        jsonb.setValue(write(parameter));
        statement.setObject(index, jsonb);
    }

    @Override
    public Map<String, String> getNullableResult(ResultSet resultSet, String columnName) throws SQLException {
        return read(resultSet.getString(columnName));
    }

    @Override
    public Map<String, String> getNullableResult(ResultSet resultSet, int columnIndex) throws SQLException {
        return read(resultSet.getString(columnIndex));
    }

    @Override
    public Map<String, String> getNullableResult(CallableStatement statement, int columnIndex) throws SQLException {
        return read(statement.getString(columnIndex));
    }

    static String write(Map<String, String> metadata) throws SQLException {
        try {
            return OBJECT_MAPPER.writeValueAsString(metadata);
        } catch (JsonProcessingException exception) {
            throw new SQLException("Unable to serialize knowledge_chunks metadata", exception);
        }
    }

    static Map<String, String> read(String value) throws SQLException {
        if (value == null) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(value, MAP_TYPE);
        } catch (JsonProcessingException exception) {
            throw new SQLException("Invalid stored knowledge_chunks metadata", exception);
        }
    }
}
