package io.yuan.octopus.domain;

import java.util.List;

public record ParseResult(
    List<ParsedFile> parsedFiles,
    List<ParseError> errors
) {}
