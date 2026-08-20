package com.knowagent.model.infrastructure.persistence.typehandler;

import com.knowagent.model.provider.EnabledModel;
import com.knowagent.model.provider.ModelCapability;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelProviderTypeHandlerTest {

    @Test
    void capabilitiesRoundTripThroughJsonb() throws SQLException {
        Set<ModelCapability> capabilities = EnumSet.of(ModelCapability.CHAT, ModelCapability.EMBEDDING);

        String json = CapabilitySetJsonbTypeHandler.write(capabilities);

        assertThat(json).isEqualTo("[\"CHAT\",\"EMBEDDING\"]");
        assertThat(CapabilitySetJsonbTypeHandler.read(json)).isEqualTo(capabilities);
    }

    @Test
    void capabilitiesRejectAnUnknownValueOnRead() {
        assertThatThrownBy(() -> CapabilitySetJsonbTypeHandler.read("[\"CHAT\",\"BOGUS\"]"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("unknown model-provider capability");
    }

    @Test
    void enabledModelsRoundTripThroughJsonb() throws SQLException {
        List<EnabledModel> models = List.of(
                new EnabledModel("gpt-4o-mini", ModelCapability.CHAT),
                new EnabledModel("text-embedding-3-small", ModelCapability.EMBEDDING));

        String json = EnabledModelsJsonbTypeHandler.write(models);

        assertThat(json).contains("\"name\":\"gpt-4o-mini\"", "\"capability\":\"CHAT\"");
        assertThat(EnabledModelsJsonbTypeHandler.read(json)).isEqualTo(models);
    }

    @Test
    void enabledModelsRejectAMalformedEntry() {
        assertThatThrownBy(() -> EnabledModelsJsonbTypeHandler.read("[{\"name\":\"x\"}]"))
                .isInstanceOf(SQLException.class);
    }
}
