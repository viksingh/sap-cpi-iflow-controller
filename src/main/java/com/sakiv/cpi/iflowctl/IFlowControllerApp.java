package com.sakiv.cpi.iflowctl;

import com.sakiv.cpi.iflowctl.config.CpiConfiguration;
import com.sakiv.cpi.iflowctl.model.IFlowTarget;
import com.sakiv.cpi.iflowctl.service.CpiHttpClient;
import com.sakiv.cpi.iflowctl.service.IFlowLifecycleService;
import com.sakiv.cpi.iflowctl.util.CsvIFlowReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

// @author Vikas Singh
// Created: 2026-06-21
// Updated: 2026-06-27 - added result summary table
public class IFlowControllerApp {

    private static final Logger log = LoggerFactory.getLogger(IFlowControllerApp.class);

    private enum Mode { STATUS, UNDEPLOY, DEPLOY }

    public static void main(String[] args) {
        log.info("========================================");
        log.info("SAP CPI iFlow Controller v1.1.0");
        log.info("========================================");

        if (args.length < 3) {
            printUsage();
            System.exit(1);
            return;
        }

        String configFile = args[0];
        String csvFile = args[1];
        Mode mode = parseMode(args[2]);
        if (mode == null) {
            System.out.println("Unknown mode: " + args[2]);
            printUsage();
            System.exit(1);
            return;
        }

        try {
            CpiConfiguration config = new CpiConfiguration(configFile);
            config.validate();
            log.info("Config source: {}", config.getConfigSource());
            log.info("Tenant: {}", config.getBaseUrl());
            log.info("Auth Type: {}", config.getAuthType());

            List<IFlowTarget> targets = new CsvIFlowReader().read(csvFile);
            if (targets.isEmpty()) {
                log.warn("No iFlows found in CSV, nothing to do.");
                return;
            }

            log.info("Mode: {} | iFlows: {}", mode, targets.size());

            int ok = 0;
            int failed = 0;
            List<Row> rows = new ArrayList<>();
            try (CpiHttpClient httpClient = new CpiHttpClient(config)) {
                IFlowLifecycleService service = new IFlowLifecycleService(config, httpClient);

                for (IFlowTarget target : targets) {
                    String pkg = target.packageName().isEmpty() ? "-" : target.packageName();
                    String iflow = target.iflowId();
                    try {
                        String iflowId = service.resolveIflowId(target.packageName(), target.iflowId());
                        if (iflowId == null) {
                            failed++;
                            rows.add(new Row("NOT_FOUND", pkg, iflow, "iFlow not found in package"));
                            log.error("iFlow '{}' not found in package '{}'", target.iflowId(), target.packageName());
                            continue;
                        }
                        switch (mode) {
                            case STATUS -> rows.add(new Row(service.getStatus(iflowId), pkg, iflow, ""));
                            case UNDEPLOY -> {
                                service.undeploy(iflowId);
                                rows.add(new Row("UNDEPLOYED", pkg, iflow, ""));
                            }
                            case DEPLOY -> {
                                service.deploy(iflowId);
                                rows.add(new Row("DEPLOYING", pkg, iflow, ""));
                            }
                        }
                        ok++;
                    } catch (Exception e) {
                        failed++;
                        rows.add(new Row("FAILED", pkg, iflow, summarize(e.getMessage())));
                        log.error("Operation failed for iFlow '{}': {}", target.iflowId(), e.getMessage());
                    }
                }
            }

            printSummary(rows, mode, ok, failed);
            log.info("Done. Success: {}, Failed: {}", ok, failed);
            if (failed > 0) {
                System.exit(2);
            }

        } catch (IllegalStateException e) {
            log.error("Configuration Error: {}", e.getMessage());
            printUsage();
            System.exit(1);
        } catch (Exception e) {
            log.error("Run failed: {}", e.getMessage(), e);
            System.exit(1);
        }
    }

    private static Mode parseMode(String arg) {
        String m = arg.toLowerCase().replaceFirst("^-+", "");
        return switch (m) {
            case "status" -> Mode.STATUS;
            case "undeploy" -> Mode.UNDEPLOY;
            case "deploy", "awaken" -> Mode.DEPLOY;
            default -> null;
        };
    }

    private static void printUsage() {
        System.out.println("""

            USAGE:
              java -jar cpi-iflow-controller.jar <config-file> <iflow-csv> <mode>

            MODES:
              -status     Show the runtime deployment status of each iFlow
              -deploy     Deploy (start) each iFlow on the runtime
              -undeploy   Stop and undeploy each iFlow from the runtime

            ARGUMENTS:
              <config-file>  Connection/credentials properties file (see config.properties.template)
              <iflow-csv>    CSV of iFlows to act on: two columns -> package name, iFlow name

            EXAMPLES:
              java -jar cpi-iflow-controller.jar config.properties iflows.csv -status
              java -jar cpi-iflow-controller.jar config.properties iflows.csv -deploy
              java -jar cpi-iflow-controller.jar config.properties iflows.csv -undeploy
            """);
    }

    private record Row(String result, String pkg, String iflow, String detail) {}

    private static String summarize(String msg) {
        if (msg == null) {
            return "";
        }
        String oneLine = msg.replaceAll("\\s+", " ").trim();
        return oneLine.length() > 60 ? oneLine.substring(0, 57) + "..." : oneLine;
    }

    private static void printSummary(List<Row> rows, Mode mode, int ok, int failed) {
        String resultHeader = mode == Mode.STATUS ? "STATUS" : "RESULT";
        String[] headers = {resultHeader, "PACKAGE", "IFLOW", "DETAIL"};

        boolean hasDetail = rows.stream().anyMatch(r -> !r.detail().isEmpty());
        int cols = hasDetail ? 4 : 3;

        int[] w = new int[4];
        for (int i = 0; i < cols; i++) {
            w[i] = headers[i].length();
        }
        for (Row r : rows) {
            w[0] = Math.max(w[0], r.result().length());
            w[1] = Math.max(w[1], r.pkg().length());
            w[2] = Math.max(w[2], r.iflow().length());
            w[3] = Math.max(w[3], r.detail().length());
        }

        StringBuilder sep = new StringBuilder("+");
        for (int i = 0; i < cols; i++) {
            sep.append("-".repeat(w[i] + 2)).append("+");
        }

        System.out.println();
        System.out.println(sep);
        printTableRow(headers, w, cols);
        System.out.println(sep);
        for (Row r : rows) {
            printTableRow(new String[]{r.result(), r.pkg(), r.iflow(), r.detail()}, w, cols);
        }
        System.out.println(sep);
        System.out.printf("Summary: %d succeeded, %d failed (%d total)%n", ok, failed, rows.size());
    }

    private static void printTableRow(String[] cells, int[] w, int cols) {
        StringBuilder sb = new StringBuilder("|");
        for (int i = 0; i < cols; i++) {
            sb.append(' ').append(pad(cells[i], w[i])).append(" |");
        }
        System.out.println(sb);
    }

    private static String pad(String s, int width) {
        return s.length() >= width ? s : s + " ".repeat(width - s.length());
    }
}
