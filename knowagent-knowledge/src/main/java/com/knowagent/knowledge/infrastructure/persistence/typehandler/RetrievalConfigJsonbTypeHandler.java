package com.knowagent.knowledge.infrastructure.persistence.typehandler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowagent.knowledge.knowledgebase.RetrievalConfig;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.postgresql.util.PGobject;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Maps the {@code retrieval_config} JSONB object to/from a validated {@link
 * RetrievalConfig}. Deserialization runs through the record's compact constructor, so
 * an invalid stored value is surfaced as a persistence failure.
 */
@MappedJdbcTypes(JdbcType.OTHER)
public final class RetrievalConfigJsonbTypeHandler extends BaseTypeHandler<RetrievalConfig> {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public void setNonNullParameter(PreparedStatement statement, int index, RetrievalConfig parameter,
                                    JdbcType jdbcType) throws SQLException {
        PGobject jsonb = new PGobject();
        jsonb.setType("jsonb");
        jsonb.setValue(write(parameter));
        statement.setObject(index, jsonb);
    }

    @Override
    public RetrievalConfig getNullableResult(ResultSet resultSet, String columnName) throws SQLException {
        return read(resultSet.getString(columnName));
    }

    @Override
    public RetrievalConfig getNullableResult(ResultSet resultSet, int columnIndex) throws SQLException {
        return read(resultSet.getString(columnIndex));
    }

    @Override
    public RetrievalConfig getNullableResult(CallableStatement statement, int columnIndex) throws SQLException {
        return read(statement.getString(columnIndex));
    }

    static String write(RetrievalConfig config) throws SQLException {
        try {
            return OBJECT_MAPPER.writeValueAsString(config);
        } catch (JsonProcessingException exception) {
            throw new SQLException("Unable to serialize knowledge_base retrieval_config", exception);
        }
    }

    static RetrievalConfig read(String value) throws SQLException {
        if (value == null) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(value, RetrievalConfig.class);
        } catch (JsonProcessingException exception) {
            throw new SQLException("Invalid stored knowledge_base retrieval_config", exception);
        }
    }
}
