package com.bitdata.sqlworkbench.session;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.io.IOException;

/** Server-owned identity policy and optimistic workspace version captured by a request. */
public record IdentityContext(String sessionId, String identityPolicy,
        @JsonDeserialize(using = IdentityContext.RevisionDeserializer.class) Long workspaceRevision) {
    public static final String LEGACY_POLICY = "unversioned-legacy";

    public static final class RevisionDeserializer extends JsonDeserializer<Long> {
        @Override public Long deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT)
                return (Long) context.handleUnexpectedToken(Long.class, parser);
            var value = parser.getBigIntegerValue();
            if (value.signum() < 0 || value.bitLength() > 63)
                throw context.weirdNumberException(value, Long.class, "Workspace revision must be a nonnegative 64-bit integer");
            return value.longValueExact();
        }
    }
}
