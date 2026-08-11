package com.knowagent.security.infrastructure.persistence.typehandler;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.sql.SQLException;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PersistenceTypeHandlerTest {
    @Test
    void permissionJsonUsesJacksonAndReturnsImmutableSet() throws Exception {
        String json = PermissionSetJsonbTypeHandler.write(Set.of("USER_READ", "USER_ADMIN"));
        Set<String> decoded = PermissionSetJsonbTypeHandler.read(json);

        assertThat(json).isEqualTo("[\"USER_ADMIN\",\"USER_READ\"]");
        assertThat(decoded).containsExactlyInAnyOrder("USER_READ", "USER_ADMIN");
        assertThatThrownBy(() -> decoded.add("MUTATE"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void permissionJsonRejectsNonArrayAndInvalidEntries() {
        assertThatThrownBy(() -> PermissionSetJsonbTypeHandler.read("{}"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("must be an array");
        assertThatThrownBy(() -> PermissionSetJsonbTypeHandler.read("[\"USER_READ\", 1]"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("non-blank strings");
        assertThatThrownBy(() -> PermissionSetJsonbTypeHandler.write(Set.of(" ")))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("non-blank strings");
    }

    @Test
    void inetHandlerReadsIpv4Ipv6PrefixAndNull() throws Exception {
        assertThat(PostgresInetTypeHandler.read("203.0.113.10"))
                .isEqualTo(InetAddress.getByName("203.0.113.10"));
        assertThat(PostgresInetTypeHandler.read("2001:db8::1/128"))
                .isEqualTo(InetAddress.getByName("2001:db8::1"));
        assertThat(PostgresInetTypeHandler.read(null)).isNull();
    }
}
