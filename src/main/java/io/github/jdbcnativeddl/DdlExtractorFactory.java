package io.github.jdbcnativeddl;

import java.util.List;

class DdlExtractorFactory {

    private static final List<DdlExtractor> EXTRACTORS = List.of(
            new PostgresDdlExtractor(),
            new OracleDdlExtractor(),
            new MssqlDdlExtractor(),
            new Db2DdlExtractor(),
            new As400DdlExtractor()
    );

    static DdlExtractor forJdbcUrl(String jdbcUrl) {
        for (DdlExtractor extractor : EXTRACTORS) {
            if (extractor.supports(jdbcUrl)) {
                return extractor;
            }
        }
        throw new IllegalArgumentException("Unsupported JDBC URL: " + jdbcUrl);
    }
}
