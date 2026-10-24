package com.bitdata.sqlworkbench.execution.model;
public class AnalyzeModeDeserializer extends com.fasterxml.jackson.databind.JsonDeserializer<String> {
    @Override public String deserialize(com.fasterxml.jackson.core.JsonParser parser,com.fasterxml.jackson.databind.DeserializationContext context) throws java.io.IOException {
        if(!parser.hasToken(com.fasterxml.jackson.core.JsonToken.VALUE_STRING)) throw com.fasterxml.jackson.databind.JsonMappingException.from(parser,"Invalid analyzeMode type");
        return parser.getText();
    }
}
