package com.bitdata.sqlworkbench.diagnostics.trace;

/** Numeric JSON tokens must not silently acquire the string-only expectedMax contract. */
public final class StrictDecimalStringDeserializer extends com.fasterxml.jackson.databind.JsonDeserializer<String> {
    @Override public String deserialize(com.fasterxml.jackson.core.JsonParser parser, com.fasterxml.jackson.databind.DeserializationContext context)
            throws java.io.IOException {
        if(!parser.hasToken(com.fasterxml.jackson.core.JsonToken.VALUE_STRING))
            return (String)context.handleUnexpectedToken(String.class,parser);
        return parser.getText();
    }
}
