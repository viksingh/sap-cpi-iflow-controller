package com.sakiv.cpi.iflowctl.util;

import com.sakiv.cpi.iflowctl.model.IFlowTarget;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

// @author Vikas Singh
// Created: 2026-06-12
public class CsvIFlowReader {

    private static final Logger log = LoggerFactory.getLogger(CsvIFlowReader.class);

    public List<IFlowTarget> read(String csvFilePath) throws IOException {
        Path path = Path.of(csvFilePath);
        if (!Files.exists(path)) {
            throw new IllegalStateException("iFlow CSV file not found: " + csvFilePath);
        }

        List<IFlowTarget> targets = new ArrayList<>();
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setTrim(true)
                .setIgnoreEmptyLines(true)
                .setCommentMarker('#')
                .build();

        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8);
             CSVParser parser = format.parse(reader)) {

            for (CSVRecord record : parser) {
                if (record.size() < 2) {
                    log.warn("Skipping malformed CSV line {}: expected 'package,iflow'", record.getRecordNumber());
                    continue;
                }
                String pkg = record.get(0).trim();
                String iflow = record.get(1).trim();

                if (isHeader(pkg, iflow)) {
                    continue;
                }
                if (iflow.isEmpty()) {
                    log.warn("Skipping CSV line {}: empty iFlow name", record.getRecordNumber());
                    continue;
                }
                targets.add(new IFlowTarget(pkg, iflow));
            }
        }

        log.info("Read {} iFlow target(s) from {}", targets.size(), csvFilePath);
        return targets;
    }

    private boolean isHeader(String pkg, String iflow) {
        String p = pkg.toLowerCase();
        String i = iflow.toLowerCase();
        return (p.equals("package") || p.equals("packageid") || p.equals("package name"))
                && (i.equals("iflow") || i.equals("iflowname") || i.equals("iflow name") || i.equals("name") || i.equals("id"));
    }
}
