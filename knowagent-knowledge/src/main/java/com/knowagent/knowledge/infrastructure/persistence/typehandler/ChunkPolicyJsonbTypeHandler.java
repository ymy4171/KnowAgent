package com.knowagent.knowledge.infrastructure.persistence.typehandler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowagent.knowledge.chunk.ChunkPolicy;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.postgresql.util.PGobject;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Maps the {@code chunk_policy} JSONB object to/from a validated {@link ChunkPolicy}.
 * Deserialization runs through the record's compact constructor, so an invalid stored
 * value is surfaced as a persistence failure rather than silently accepted.
 */
@MappedJdbcTypes(JdbcType.OTHER)
public final class ChunkPolicyJsonbTypeHandler extends BaseTypeHandler<ChunkPolicy> {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public void setNonNullParameter(PreparedStatement statement, int index, ChunkPolicy parameter,
                                    JdbcType jdbcType) throws SQLException {
        PGobject jsonb = new PGobject();
        jsonb.setType("jsonb");
        jsonb.setValue(write(parameter));
        statement.setObject(index, jsonb);
    }

    @Override
    public ChunkPolicy getNullableResult(ResultSet resultSet, String columnName) throws SQLException {
        return read(resultSet.getString(columnName));
    }

    @Override
    public ChunkPolicy getNullableResult(ResultSet resultSet, int columnIndex) throws SQLException {
        return read(resultSet.getString(columnIndex));
    }

    @Override
    public ChunkPolicy getNullableResult(CallableStatement statement, int columnIndex) throws SQLException {
        return read(statement.getString(columnIndex));
    }

    static String write(ChunkPolicy policy) throws SQLException {
        try {
            return OBJECT_MAPPER.writeValueAsString(policy);
        } catch (JsonProcessingException exception) {
            throw new SQLException("Unable to serialize knowledge_base chunk_policy", exception);
        }
    }

    static ChunkPolicy read(String value) throws SQLException {
        if (value == null) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(value, ChunkPolicy.class);
        } catch (JsonProcessingException exception) {
            throw new SQLException("Invalid stored knowledge_base chunk_policy", exception);
        }
    }
}
