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
import java.util.List;

/**
 * Maps the {@code section_path} JSONB array to/from a {@link List}{@code <String>} of
 * heading path segments (e.g. {@code ["1","1.1"]}). A null column decodes to null; the
 * domain model treats an absent path as the empty list.
 */
@MappedJdbcTypes(JdbcType.OTHER)
public final class StringListJsonbTypeHandler extends BaseTypeHandler<List<String>> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> LIST_TYPE = new TypeReference<>() {
    };

    @Override
    public void setNonNullParameter(PreparedStatement statement, int index, List<String> parameter,
                                    JdbcType jdbcType) throws SQLException {
        PGobject jsonb = new PGobject();
        jsonb.setType("jsonb");
        jsonb.setValue(write(parameter));
        statement.setObject(index, jsonb);
    }

    @Override
    public List<String> getNullableResult(ResultSet resultSet, String columnName) throws SQLException {
        return read(resultSet.getString(columnName));
    }

    @Override
    public List<String> getNullableResult(ResultSet resultSet, int columnIndex) throws SQLException {
        return read(resultSet.getString(columnIndex));
    }

    @Override
    public List<String> getNullableResult(CallableStatement statement, int columnIndex) throws SQLException {
        return read(statement.getString(columnIndex));
    }

    static String write(List<String> segments) throws SQLException {
        try {
            return OBJECT_MAPPER.writeValueAsString(segments);
        } catch (JsonProcessingException exception) {
            throw new SQLException("Unable to serialize knowledge_chunks section_path", exception);
        }
    }

    static List<String> read(String value) throws SQLException {
        if (value == null) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(value, LIST_TYPE);
        } catch (JsonProcessingException exception) {
            throw new SQLException("Invalid stored knowledge_chunks section_path", exception);
        }
    }
}
