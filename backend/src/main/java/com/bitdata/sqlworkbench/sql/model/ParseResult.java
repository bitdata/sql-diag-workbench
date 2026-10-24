package com.bitdata.sqlworkbench.sql.model;

import java.util.List;

public record ParseResult(FragmentNode tree, List<DecomposeOption> options, List<ParseError> errors, DecompositionResult.Scan scan) {
    public ParseResult(FragmentNode tree,List<DecomposeOption> options,List<ParseError> errors) {
        this(tree,options,errors,new DecompositionResult.Scan("COMPLETE",List.of()));
    }

    public record ParseError(String code, String message, ParseErrorDetail detail) {}

    public record ParseErrorDetail(ParsePosition position, String token, String suggestion) {}

    public record ParsePosition(int line, int column) {}
}
