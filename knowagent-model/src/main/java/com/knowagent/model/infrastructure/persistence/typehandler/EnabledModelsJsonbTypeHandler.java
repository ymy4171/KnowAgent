package com.knowagent.model.infrastructure.persistence.typehandler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.knowagent.model.provider.EnabledModel;
import com.knowagent.model.provider.ModelCapability;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.postgresql.util.PGobject;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Maps the {@code enabled_models} JSONB object array to a {@link List} of
 * {@link EnabledModel}. Each element is {@code {"name": "...", "capability": "..."}}.
 */
@MappedJdbcTypes(JdbcType.OTHER)
public final class EnabledModelsJsonbTypeHandler extends BaseTypeHandler<List<EnabledModel>> {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public void setNonNullParameter(PreparedStatement statement, int index, List<EnabledModel> parameter,
                                    JdbcType jdbcType) throws SQLException {
        PGobject jsonb = new PGobject();
        jsonb.setType("jsonb");
        jsonb.setValue(write(parameter));
        statement.setObject(index, jsonb);
    }

    @Override
    public List<EnabledModel> getNullableResult(ResultSet resultSet, String columnName) throws SQLException {
        return read(resultSet.getString(columnName));
    }

    @Override
    public List<EnabledModel> getNullableResult(ResultSet resultSet, int columnIndex) throws SQLException {
        return read(resultSet.getString(columnIndex));
    }

    @Override
    public List<EnabledModel> getNullableResult(CallableStatement statement, int columnIndex) throws SQLException {
        return read(statement.getString(columnIndex));
    }

    static String write(List<EnabledModel> enabledModels) throws SQLException {
        ArrayNode array = OBJECT_MAPPER.createArrayNode();
        for (EnabledModel enabledModel : enabledModels) {
            ObjectNode entry = OBJECT_MAPPER.createObjectNode();
            entry.put("name", enabledModel.name());
            entry.put("capability", enabledModel.capability().name());
            array.add(entry);
        }
        return array.toString();
    }

    static List<EnabledModel> read(String value) throws SQLException {
        if (value == null) {
            return null;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(value);
            if (!root.isArray()) {
                throw new SQLException("model_providers.enabled_models JSONB must be an array");
            }
            List<EnabledModel> enabledModels = new ArrayList<>();
            for (JsonNode item : root) {
                if (!item.isObject() || !item.hasNonNull("name") || !item.hasNonNull("capability")) {
                    throw new SQLException("enabled_models entries must be {name, capability} objects");
                }
                String name = item.get("name").asText();
                ModelCapability capability;
                try {
                    capability = ModelCapability.valueOf(item.get("capability").asText());
                } catch (IllegalArgumentException exception) {
                    throw new SQLException("unknown enabled_models capability '" + item.get("capability").asText()
                            + "'", exception);
                }
                enabledModels.add(new EnabledModel(name, capability));
            }
            return List.copyOf(enabledModels);
        } catch (JsonProcessingException exception) {
            throw new SQLException("Unable to deserialize model-provider enabled_models", exception);
        }
    }
}
