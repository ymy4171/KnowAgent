package com.knowagent.model.infrastructure.persistence.typehandler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowagent.model.provider.ModelCapability;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.postgresql.util.PGobject;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * Maps the {@code capabilities} JSONB string array to an immutable
 * {@link Set}&lt;{@link ModelCapability}&gt;. Serializes in enum order so the stored
 * value is deterministic; rejects unknown or non-string entries on read.
 */
@MappedJdbcTypes(JdbcType.OTHER)
public final class CapabilitySetJsonbTypeHandler extends BaseTypeHandler<Set<ModelCapability>> {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public void setNonNullParameter(PreparedStatement statement, int index, Set<ModelCapability> parameter,
                                    JdbcType jdbcType) throws SQLException {
        PGobject jsonb = new PGobject();
        jsonb.setType("jsonb");
        jsonb.setValue(write(parameter));
        statement.setObject(index, jsonb);
    }

    @Override
    public Set<ModelCapability> getNullableResult(ResultSet resultSet, String columnName) throws SQLException {
        return read(resultSet.getString(columnName));
    }

    @Override
    public Set<ModelCapability> getNullableResult(ResultSet resultSet, int columnIndex) throws SQLException {
        return read(resultSet.getString(columnIndex));
    }

    @Override
    public Set<ModelCapability> getNullableResult(CallableStatement statement, int columnIndex) throws SQLException {
        return read(statement.getString(columnIndex));
    }

    static String write(Set<ModelCapability> capabilities) throws SQLException {
        try {
            TreeSet<String> names = new TreeSet<>();
            for (ModelCapability capability : capabilities) {
                names.add(capability.name());
            }
            return OBJECT_MAPPER.writeValueAsString(names);
        } catch (JsonProcessingException exception) {
            throw new SQLException("Unable to serialize model-provider capabilities", exception);
        }
    }

    static Set<ModelCapability> read(String value) throws SQLException {
        if (value == null) {
            return null;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(value);
            if (!root.isArray()) {
                throw new SQLException("model_providers.capabilities JSONB must be an array");
            }
            EnumSet<ModelCapability> capabilities = EnumSet.noneOf(ModelCapability.class);
            for (JsonNode item : root) {
                if (!item.isTextual()) {
                    throw new SQLException("model-provider capability entries must be strings");
                }
                try {
                    capabilities.add(ModelCapability.valueOf(item.textValue()));
                } catch (IllegalArgumentException exception) {
                    throw new SQLException("unknown model-provider capability '" + item.textValue() + "'", exception);
                }
            }
            return Set.copyOf(new LinkedHashSet<>(capabilities));
        } catch (JsonProcessingException exception) {
            throw new SQLException("Unable to deserialize model-provider capabilities", exception);
        }
    }
}
